package watch;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import static watch.Json.obj;

/** Run this main class in IntelliJ. Java 21+; no external dependencies. */
public final class PumpkinWatch implements AutoCloseable {
    private record Session(String code,String id) {}
    private final Map<String,Game> rooms=new ConcurrentHashMap<>();
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private final Map<String,Object> connections=new ConcurrentHashMap<>();
    private final Map<String,long[]> rates=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer server;
    private volatile boolean running=true;
    public PumpkinWatch(int port)throws IOException{
        server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),0);server.setExecutor(workers);server.createContext("/",this::handle);
        clock.scheduleAtFixedRate(()->{
            try{long now=System.currentTimeMillis();for(Game room:rooms.values())synchronized(room){
                if(room.players.stream().anyMatch(p->p.connected)){room.lastSeen=now;room.tick(.05);}
                if(room.phase.equals("lobby")) {
                    room.players.removeIf(p->!p.connected&&p.disconnectedAt>0&&now-p.disconnectedAt>60000);
                    sessions.entrySet().removeIf(e->e.getValue().code.equals(room.code)&&room.player(e.getValue().id)==null);
                    if(room.player(room.host)==null)room.host=room.players.stream().map(p->p.id).findFirst().orElse(null);
                }
                if(now-room.lastSeen>30*60*1000){rooms.remove(room.code);sessions.entrySet().removeIf(e->e.getValue().code.equals(room.code));}
            }rates.entrySet().removeIf(e->now-e.getValue()[0]>60000);}catch(Exception e){e.printStackTrace();}
        },50,50,TimeUnit.MILLISECONDS);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    private String token(){byte[] bytes=new byte[24];random.nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    private void json(HttpExchange x,int status,Object data)throws IOException{
        byte[] bytes=Json.encode(data).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}
    }
    private String query(HttpExchange x,String key){String raw=x.getRequestURI().getRawQuery();if(raw==null)return "";for(String part:raw.split("&")){String[] pair=part.split("=",2);if(pair[0].equals(key))return pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";}return "";}
    private void stream(HttpExchange x)throws IOException{
        String token=query(x,"token");Session s=sessions.get(token);Game room=s==null?null:rooms.get(s.code);
        if(room==null){json(x,401,obj("error","Your room expired. Plant a new patch."));return;}
        synchronized(room){if(room.player(s.id)==null){json(x,401,obj("error","Your lobby spot expired. Join again."));return;}}
        Object connection=new Object();connections.put(token,connection);
        x.getResponseHeaders().set("Content-Type","text/event-stream");x.getResponseHeaders().set("Cache-Control","no-cache, no-transform");x.getResponseHeaders().set("X-Accel-Buffering","no");x.sendResponseHeaders(200,0);
        synchronized(room){room.player(s.id).connected=true;room.player(s.id).disconnectedAt=0;if(room.player(room.host)==null||!room.player(room.host).connected)room.host=s.id;}
        try(var out=x.getResponseBody()){
            out.write("retry: 1500\n\n".getBytes(StandardCharsets.UTF_8));
            while(running&&connections.get(token)==connection&&sessions.containsKey(token)){
                String state;synchronized(room){state=Json.encode(room.snapshot());}
                out.write(("data: "+state+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();Thread.sleep(100);
            }
        }catch(IOException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}
        finally{if(connections.remove(token,connection))disconnect(s);x.close();}
    }
    private void disconnect(Session s){Game room=rooms.get(s.code);if(room!=null)synchronized(room){Game.Player p=room.player(s.id);if(p!=null){p.connected=false;p.disconnectedAt=System.currentTimeMillis();p.ix=p.iy=0;p.action=false;p.target=null;if(p.id.equals(room.host))room.host=room.players.stream().filter(q->q.connected).map(q->q.id).findFirst().orElse(p.id);}}}
    private void handle(HttpExchange x)throws IOException{
        try{
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(method.equals("GET")&&path.equals("/api/events")){stream(x);return;}
            if(method.equals("GET")&&path.equals("/api/config")){json(x,200,obj("types",Game.TYPES,"charms",Game.CHARMS));return;}
            if(method.equals("POST")&&path.startsWith("/api/")){
                String origin=x.getRequestHeaders().getFirst("Origin"),host=x.getRequestHeaders().getFirst("Host");
                if(origin!=null&&!origin.equals("http://"+host)&&!origin.equals("https://"+host)){json(x,403,obj("error","Please use the game page to play."));return;}
                long now=System.currentTimeMillis();long[] rate=rates.computeIfAbsent(x.getRemoteAddress().getAddress().getHostAddress(),k->new long[]{now,0});
                synchronized(rate){if(now-rate[0]>1000){rate[0]=now;rate[1]=0;}if(++rate[1]>250){json(x,429,obj("error","Too many actions. Try again in a moment."));return;}}
                byte[] bytes=x.getRequestBody().readNBytes(4097);Game.require(bytes.length<=4096,"Request is too large.");Map<String,Object>d=Json.parseObject(new String(bytes,StandardCharsets.UTF_8));
                if(path.equals("/api/create")||path.equals("/api/join")){
                    Game room;
                    if(path.equals("/api/create")){
                        Game.require(rooms.size()<100,"The farm is busy. Try again later.");String code;do{code=token().substring(0,5).toUpperCase(Locale.ROOT);}while(rooms.containsKey(code));room=new Game(code,Boolean.TRUE.equals(d.get("practice")));rooms.put(code,room);
                    }else{room=rooms.get(Game.str(d,"code","").strip().toUpperCase(Locale.ROOT));Game.require(room!=null,"No patch with that code. Check it and try again.");}
                    synchronized(room){String id=UUID.randomUUID().toString(),token=token();Game.Player p=room.addPlayer(id,Game.str(d,"name","Little sprout"),Game.str(d,"skin","pumpkin"));p.connected=false;p.disconnectedAt=System.currentTimeMillis();sessions.put(token,new Session(room.code,id));json(x,200,obj("token",token,"id",id,"state",room.snapshot()));}return;
                }
                String auth=x.getRequestHeaders().getFirst("Authorization"),token=auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):"";Session s=sessions.get(token);Game room=s==null?null:rooms.get(s.code);
                if(room==null){json(x,401,obj("error","Your room expired. Plant a new patch."));return;}
                synchronized(room){
                    if(room.player(s.id)==null){json(x,401,obj("error","Your lobby spot expired. Join again."));return;}
                    if(path.equals("/api/resume")){json(x,200,obj("token",token,"id",s.id,"state",room.snapshot()));return;}
                    if(path.equals("/api/leave")){sessions.remove(token);connections.remove(token);disconnect(s);if(room.phase.equals("lobby"))room.players.removeIf(p->p.id.equals(s.id));json(x,200,obj("ok",true));return;}
                    if(path.equals("/api/action")){room.command(s.id,d);json(x,200,obj("ok",true));return;}
                }
                json(x,404,obj("error","Unknown action."));return;
            }
            Map<String,String> files=Map.of("/","index.html","/index.html","index.html","/app.js","app.js","/art.js","art.js","/style.css","style.css","/favicon.svg","favicon.svg");
            String file=files.get(path);if(!method.equals("GET")||file==null){json(x,404,obj("error","Not found."));return;}
            byte[] content;try(InputStream in=PumpkinWatch.class.getResourceAsStream("/public/"+file)){content=in==null?Files.readAllBytes(Path.of("src/main/resources/public",file)):in.readAllBytes();}
            String ext=file.substring(file.lastIndexOf('.')+1);x.getResponseHeaders().set("Content-Type",Map.of("html","text/html; charset=utf-8","js","text/javascript; charset=utf-8","css","text/css; charset=utf-8","svg","image/svg+xml").get(ext));
            x.getResponseHeaders().set("Cache-Control","no-cache");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.getResponseHeaders().set("Referrer-Policy","no-referrer");x.sendResponseHeaders(200,content.length);try(var out=x.getResponseBody()){out.write(content);}
        }catch(IllegalArgumentException e){json(x,400,obj("error",e.getMessage()));}catch(Exception e){e.printStackTrace();if(x.getResponseCode()<0)json(x,500,obj("error","Something rustled in the server. Try again."));else x.close();}
    }
    @Override public void close(){running=false;clock.shutdownNow();server.stop(0);workers.shutdownNow();}
    public static void main(String[] args)throws IOException{
        int port=Integer.parseInt(System.getenv().getOrDefault("PORT","3000"));PumpkinWatch app=new PumpkinWatch(port);app.start();Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        System.out.println("\n  PUMPKIN WATCH · A little courage after dark\n  Open http://localhost:"+app.port());
        for(var it=NetworkInterface.getNetworkInterfaces();it.hasMoreElements();)for(var addresses=it.nextElement().getInetAddresses();addresses.hasMoreElements();){InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress())System.out.println("  Same Wi-Fi: http://"+a.getHostAddress()+":"+app.port());}
        System.out.println("  Share that address with friends, then share your room code.\n");
    }
}
