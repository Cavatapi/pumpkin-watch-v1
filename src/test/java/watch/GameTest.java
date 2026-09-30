package watch;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static watch.Json.obj;

/** Dependency-free tests; run run.ps1 -Test or run this class in IntelliJ. */
public final class GameTest {
    static int checks;
    static void check(boolean condition,String description){checks++;if(!condition)throw new AssertionError(description);}
    static void rejects(Runnable action,String description){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,description);}
    static Game setup(int count){Game g=new Game("TEST1",count==1,new Random(71));for(int i=0;i<count;i++){g.addPlayer("p"+i,"Sprout "+i,"pumpkin");g.command("p"+i,obj("type","ready","ready",true));}g.command("p0",obj("type","start"));return g;}
    static void advance(Game g,double seconds){for(int i=0;i<Math.ceil(seconds/.05);i++)g.tick(.05);}
    static void simulation(){
        Game lobby=new Game("ROOM1",false);lobby.addPlayer("a","A","pumpkin");lobby.command("a",obj("type","ready","ready",true));rejects(()->lobby.command("a",obj("type","start")),"Normal room needs two players");lobby.addPlayer("b","B","scarecrow");rejects(()->lobby.command("a",obj("type","start")),"Everyone must ready");lobby.command("b",obj("type","ready","ready",true));rejects(()->lobby.command("b",obj("type","start")),"Only host starts");lobby.command("a",obj("type","start"));check(lobby.squadSize==2&&lobby.seeds==220,"Two-player initial seeds");rejects(()->lobby.addPlayer("c","Late","pumpkin"),"No late joins");
        Game full=new Game("FULL1",false);for(int i=0;i<8;i++)full.addPlayer("p"+i,"P","pumpkin");rejects(()->full.addPlayer("ninth","P","pumpkin"),"Eight player cap");
        final Game build=setup(2);Game g=build;Game.Player p=g.player("p0");rejects(()->build.command("p0",obj("type","build","plot",0,"build","cannon")),"Build requires proximity");p.x=670;p.y=340;g.seeds=40;g.command("p0",obj("type","build","plot",0,"build","cannon"));check(g.seeds==0&&g.plots.get(0).tower!=null,"Build spends shared seeds");rejects(()->build.command("p0",obj("type","build","plot",0,"build","cannon")),"Occupied plot protected");rejects(()->build.command("p0",obj("type","upgrade","plot",0)),"Insufficient upgrade funds protected");
        Game.Tower t=g.plots.get(0).tower;t.hp=30;g.charms.put("patch",1);g.command("p0",obj("type","input","action",true));g.tick(.5);check(t.hp==51&&t.charged&&p.repair==21&&g.seeds==0,"Repair costs time and charges Patchwork");g.seeds=100;g.command("p0",obj("type","upgrade","plot",0));check(t.level==2&&g.seeds==70&&t.hp==t.maxHp,"Upgrade scales health and spends correct amount");
        p.ghost=1;rejects(()->build.command("p0",obj("type","upgrade","plot",0)),"Ghosts cannot build");advance(g,1.05);check(p.ghost==0&&p.hp==100,"Ghost respawn");
        g=setup(2);p=g.player("p0");Game.Player other=g.player("p1");p.x=other.x=300;p.y=other.y=200;g.drops.add(new Game.Drop(50,300,200,25));int bank=g.seeds;g.harvestTimer=100;g.tick(.05);check(g.seeds==bank+25&&p.collected+other.collected==25,"Simultaneous pickup credited exactly once");
        g=setup(1);p=g.player("p0");p.x=400;p.y=260;g.command("p0",obj("type","input","x",1,"y",1));g.tick(.05);check(Math.abs(Math.hypot(p.x-400,p.y-260)-6)<.001,"Diagonal speed normalized");p.inputAt=0;double x=p.x;g.tick(.05);check(p.x==x,"Stale input stops movement");g.command("p0",obj("type","move","x",500,"y",260));advance(g,2);check(Math.abs(p.x-500)<5,"Tap destination reached");
        g=setup(1);g.time=.01;g.tick(.05);check(g.phase.equals("wave")&&g.wave==1,"Dusk transitions to wave");g.time=0;g.enemies.clear();g.tick(.05);check(g.phase.equals("vote")&&g.options.size()==3&&g.score.equals(BigInteger.valueOf(500)),"Wave ends into charm vote");String charm=(String)g.options.get(0).get("id");g.command("p0",obj("type","vote","charm",charm));g.time=0;g.tick(.05);check(g.wave==2&&g.stack(charm)==1,"Vote applies charm and advances wave");
        Game vote=setup(2);vote.phase="vote";vote.options=new ArrayList<>(Game.CHARMS.subList(0,3));vote.time=0;vote.command("p0",obj("type","vote","charm","candle"));vote.command("p1",obj("type","vote","charm","patch"));vote.tick(.05);check(vote.stack("harvest")==0&&vote.charms.size()==1,"Tie resolves only among tied choices");
        Game combat=setup(1);combat.phase="wave";combat.time=60;combat.spawnTimer=100;combat.plots.get(0).tower=new Game.Tower("cannon",150);combat.charms.put("ember",2);combat.charms.put("harvest",1);Game.Tower cannon=combat.plots.get(0).tower;cannon.cooldown=0;cannon.shots=4;Game.Enemy a=new Game.Enemy(1,0,1000,0,false,false);a.x=750;a.y=300;Game.Enemy b=new Game.Enemy(2,0,1000,0,false,false);b.x=765;b.y=305;combat.enemies.add(a);combat.enemies.add(b);combat.tick(.05);advance(combat,.4);check(Math.abs(a.hp-(1000-28*1.4*1.4*3))<.001&&a.hp==b.hp,"Repeated multipliers and fifth-shot splash combine");
        combat=setup(1);combat.plots.get(0).tower=new Game.Tower("flower",90);combat.plots.get(0).tower.cooldown=0;combat.charms.put("moon",2);combat.harvestTimer=100;combat.tick(.05);check(combat.drops.get(0).value==12,"Moonflower economy scales with charm stacks");
        Game small=setup(2),large=setup(8);small.wave=large.wave=3;small.spawn(true);large.spawn(true);check(large.enemies.get(0).hp>small.enemies.get(0).hp,"Enemy health scales with starting team size");
        Game lose=setup(1);lose.phase="wave";lose.time=0;lose.wake=95;Game.Enemy hit=new Game.Enemy(1,0,100,0,false,false);hit.x=480;hit.y=300;lose.enemies.add(hit);lose.tick(.05);check(lose.phase.equals("lost"),"Farmhouse wake causes defeat");
        Game boss=setup(1);boss.phase="wave";boss.wave=5;boss.time=0;boss.spawn(true);boss.enemies.get(0).x=480;boss.enemies.get(0).y=300;boss.tick(.05);check(boss.phase.equals("lost"),"King reaching house cannot count as victory");
        Game win=setup(1);win.phase="wave";win.wave=5;win.time=0;win.spawn(true);win.damage(win.enemies.get(0),1e9,new Game.Point(0,0));win.tick(.05);check(win.phase.equals("won")&&win.score.equals(BigInteger.valueOf(12600)),"King kill, clear and sleep score");win.command("p0",obj("type","rematch"));check(win.phase.equals("lobby")&&win.score.signum()==0&&!win.player("p0").ready,"Rematch resets run");
        win.score=BigInteger.TEN.pow(100).add(BigInteger.ONE);String encoded=Json.encode(win.snapshot());check(encoded.contains("\"score\":\""+win.score+"\""),"Huge scores retain exact precision as strings");
        Map<String,Object> roundTrip=Json.parseObject(Json.encode(obj("name","\"pumpkin\\\n🎃","items",List.of(true,4,"x"))));check(roundTrip.get("name").equals("\"pumpkin\\\n🎃"),"JSON string round trip");rejects(()->Json.parseObject("{\"x\":NaN}"),"Non-finite JSON rejected");

        Game radial=setup(1);radial.phase="wave";radial.time=70;radial.spawnTimer=100;radial.harvestTimer=100;
        boolean[] quadrants=new boolean[4];for(int i=0;i<100;i++){radial.spawn(false);Game.Enemy e=radial.enemies.get(radial.enemies.size()-1);check(Math.abs(Game.dist(e,Game.HOME)-Game.SPAWN_RADIUS)<.001,"Spawn on circular perimeter");quadrants[(e.x<480?1:0)+(e.y<300?2:0)]=true;}
        check(quadrants[0]&&quadrants[1]&&quadrants[2]&&quadrants[3],"Enemies spawn on all sides");radial.enemies.clear();
        Game.Enemy north=new Game.Enemy(991,-Math.PI/2,1000,20,false,false);radial.enemies.add(north);double before=Game.dist(north,Game.HOME);radial.tick(.05);check(Game.dist(north,Game.HOME)<before&&north.y>30,"Enemy walks toward central farmhouse");radial.enemies.clear();
        Game.Plot east=radial.plots.get(0);Game.Enemy front=new Game.Enemy(992,0,1000,0,false,false);front.x=east.x+100;front.y=east.y;Game.Enemy behind=new Game.Enemy(993,0,1000,0,false,false);behind.x=east.x-50;behind.y=east.y;Game.Enemy flank=new Game.Enemy(994,0,1000,0,false,false);flank.x=east.x;flank.y=east.y+100;radial.enemies.addAll(List.of(front,behind,flank));
        radial.fireFan(east,28,false);check(radial.projectiles.size()==9,"Nine projectiles per spray");check(Math.abs(radial.projectiles.get(8).angle-radial.projectiles.get(0).angle-Math.PI)<.001,"Spray spans 180 degrees");
        radial.moveProjectiles(.05);check(front.hp==1000,"Projectiles take time to travel");for(int i=0;i<8;i++)radial.moveProjectiles(.05);
        check(front.hp==972&&flank.hp==972,"One spray hits multiple directions");check(behind.hp==1000,"Outward spray does not hit inward enemies");
        radial.moveProjectiles(2);check(radial.projectiles.isEmpty(),"Projectiles expire at range");
        radial.enemies.clear();east.tower=new Game.Tower("cannon",150);east.tower.cooldown=0;radial.enemies.add(behind);radial.tick(.05);check(radial.projectiles.isEmpty(),"Cannons do not trigger on enemies behind them");
        radial.enemies.clear();east.tower=new Game.Tower("fence",440);Game.Enemy blocked=new Game.Enemy(995,0,1000,20,false,false);blocked.x=east.x+42;blocked.y=east.y+5;radial.enemies.add(blocked);double blockedX=blocked.x;radial.tick(.05);check(blocked.x==blockedX&&east.tower.hp<440,"Bramble blocks nearby radial approach");
        System.out.println("Simulation checks passed.");
    }
    static final HttpClient client=HttpClient.newHttpClient();static String base;
    static HttpResponse<String> post(String route,String token,Map<String,Object> data)throws Exception{var b=HttpRequest.newBuilder(URI.create(base+route)).header("Content-Type","application/json");if(token!=null)b.header("Authorization","Bearer "+token);return client.send(b.POST(HttpRequest.BodyPublishers.ofString(Json.encode(data))).build(),HttpResponse.BodyHandlers.ofString());}
    static final class Feed implements AutoCloseable {
        final InputStream input;final BufferedReader reader;
        Feed(String token)throws Exception{var r=client.send(HttpRequest.newBuilder(URI.create(base+"events?token="+token)).build(),HttpResponse.BodyHandlers.ofInputStream());check(r.statusCode()==200,"Event stream authenticated");input=r.body();reader=new BufferedReader(new InputStreamReader(input));next();}
        Map<String,Object> next()throws Exception{for(String line;(line=reader.readLine())!=null;)if(line.startsWith("data: "))return Json.parseObject(line.substring(6));throw new EOFException();}
        public void close()throws IOException{input.close();}
    }
    @SuppressWarnings("unchecked") static void integration()throws Exception{
        try(PumpkinWatch app=new PumpkinWatch(0)){app.start();base="http://localhost:"+app.port()+"/api/";
            var page=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+app.port()+"/")).build(),HttpResponse.BodyHandlers.ofString());check(page.statusCode()==200&&page.body().contains("Keep the farmer"),"Static game page served");
            check(post("action",null,obj("type","start")).statusCode()==401,"Unauthenticated action blocked");
            var first=Json.parseObject(post("create",null,obj("name","Host")).body());String one=(String)first.get("token");String code=(String)((Map<String,Object>)first.get("state")).get("code");
            var second=Json.parseObject(post("join",null,obj("code",code,"name","Guest")).body());String two=(String)second.get("token");
            try(Feed f1=new Feed(one);Feed f2=new Feed(two)){
                check(post("action",two,obj("type","start")).statusCode()==400,"Guest cannot start over HTTP");
                post("action",one,obj("type","ready","ready",true));post("action",two,obj("type","ready","ready",true));check(post("action",one,obj("type","start")).statusCode()==200,"Ready multiplayer room starts");
                var snap=Json.parseObject(post("resume",two,obj()).body());var state=(Map<String,Object>)snap.get("state");check(state.get("phase").equals("dusk")&&((List<?>)state.get("players")).size()==2,"Clients share one authoritative room");
                check(post("join",null,obj("code",code,"name","Late")).statusCode()==400,"Late joining rejected by HTTP");
                check(post("action",one,obj("type","build","plot",0,"build","cannon")).statusCode()==400,"Remote building prevented");
                check(post("action",one,obj("type","move","x",260,"y",220)).statusCode()==200,"Movement command accepted");
                String guest=(String)second.get("id");post("leave",one,obj());state=(Map<String,Object>)Json.parseObject(post("resume",two,obj()).body()).get("state");check(state.get("host").equals(guest),"Host transfers on leaving");check(post("resume",one,obj()).statusCode()==401,"Leaving invalidates token");
            }
            var stale=Json.parseObject(post("create",null,obj("practice",true)).body());String solo=(String)stale.get("token");try(Feed ignored=new Feed(solo)){post("action",solo,obj("type","ready","ready",true));check(post("action",solo,obj("type","start")).statusCode()==200,"Solo practice works through real connection");}
            List<String> eight=new ArrayList<>();List<Feed> feeds=new ArrayList<>();
            try{
                var party=Json.parseObject(post("create",null,obj("name","P0")).body());String partyCode=(String)((Map<String,Object>)party.get("state")).get("code");eight.add((String)party.get("token"));
                for(int i=1;i<8;i++)eight.add((String)Json.parseObject(post("join",null,obj("code",partyCode,"name","P"+i)).body()).get("token"));
                check(post("join",null,obj("code",partyCode)).statusCode()==400,"Ninth network player rejected");
                for(String t:eight){feeds.add(new Feed(t));post("action",t,obj("type","ready","ready",true));}
                check(post("action",eight.get(0),obj("type","start")).statusCode()==200,"Eight connected clients start together");
                for(String t:eight.subList(0,2))post("action",t,obj("type","move","x",635,"y",300));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean arrived=false;
                while(System.nanoTime()<deadline){var snap=(Map<String,Object>)Json.parseObject(post("resume",eight.get(1),obj()).body()).get("state");var ps=(List<Map<String,Object>>)snap.get("players");if(ps.subList(0,2).stream().allMatch(p->Math.hypot(((Number)p.get("x")).doubleValue()-670,((Number)p.get("y")).doubleValue()-300)<88)){arrived=true;break;}Thread.sleep(50);}
                check(arrived,"Network movement reaches build range");
                try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
                    var a=executor.submit(()->post("action",eight.get(0),obj("type","build","plot",0,"build","cannon")).statusCode());
                    var b=executor.submit(()->post("action",eight.get(1),obj("type","build","plot",0,"build","cannon")).statusCode());
                    check((a.get()==200?1:0)+(b.get()==200?1:0)==1,"Concurrent construction only succeeds once");
                }
                var shared=(Map<String,Object>)Json.parseObject(post("resume",eight.get(7),obj()).body()).get("state");check(((Number)shared.get("seeds")).intValue()==360,"Shared funds deducted exactly once across eight clients");
            }finally{for(Feed f:feeds)f.close();}
        }System.out.println("HTTP and event-stream checks passed.");
    }
    public static void main(String[]args)throws Exception{simulation();integration();System.out.println("PASS · "+checks+" checks");}
}

