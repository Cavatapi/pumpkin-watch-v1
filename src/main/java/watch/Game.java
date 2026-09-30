package watch;

import java.math.BigInteger;
import java.util.*;
import static watch.Json.obj;

/** Authoritative simulation. All access is synchronized by the room's server lock. */
public final class Game {
    static final int DUSK=60, WAVE=90, VOTE=20, WAVES=5, MAX_PLAYERS=8;
    static final Point HOME=new Point(480,300);
    static final double HOME_RADIUS=46, SPAWN_RADIUS=270;
    static final int FAN_PELLETS=9;
    public static final class Spec {
        public String name; public int cost; public double hp,damage,range,cooldown;
        Spec(String n,int c,double h,double d,double r,double cd) {name=n;cost=c;hp=h;damage=d;range=r;cooldown=cd;}
    }
    public static final Map<String,Spec> TYPES=new LinkedHashMap<>();
    public static final List<Map<String,Object>> CHARMS=List.of(
        obj("id","candle","name","Candle club","icon","✦","tag","NEIGHBOR BONUS","description","Lanterns fire 25% faster per nearby lantern. Each stack adds another 25%.","color","#edb867"),
        obj("id","patch","name","Patchwork magic","icon","✿","tag","REPAIR SYNERGY","description","Repairs charge the next attack for ×3 damage. Each stack adds ×2.","color","#d7a2ca"),
        obj("id","harvest","name","Big harvest","icon","◈","tag","EVERY FIFTH SHOT","description","Every fifth cannon shot deals ×3 splash damage. Each stack adds ×2.","color","#efa66f"),
        obj("id","moon","name","Moonlit interest","icon","☾","tag","ECONOMY","description","Moonflowers produce 50% more seeds per stack. Invest now, bloom later.","color","#b9c9ee"),
        obj("id","thorn","name","A little prickly","icon","♧","tag","BRAMBLE BONUS","description","Bramble damage doubles per stack. Fences gain 50% base health per stack.","color","#b8cc8e"),
        obj("id","ember","name","Ember arithmetic","icon","×","tag","MULTIPLIER","description","All defense damage ×1.4. Repeated copies multiply together.","color","#f08a72")
    );
    static { TYPES.put("cannon",new Spec("Pumpkin cannon",40,150,28,230,1.35)); TYPES.put("lantern",new Spec("Lantern sprout",55,100,11,170,.46)); TYPES.put("fence",new Spec("Bramble fence",25,440,4,38,1)); TYPES.put("flower",new Spec("Moonflower",45,90,0,0,7)); }
    public static class Point { public double x,y; Point(double x,double y){this.x=x;this.y=y;} }
    public static final class Player extends Point {
        public String id,name,skin; public double hp=100,ghost,repair,collected; public boolean ready,connected=true;
        double ix,iy; boolean action; Point target; long inputAt; long disconnectedAt;
        Player(String id,String name,String skin,int index){super(480+76*Math.cos(index*Math.PI/4),300+76*Math.sin(index*Math.PI/4));this.id=id;this.name=name;this.skin=skin;}
    }
    public static final class Tower {
        public String type; public int level=1,shots; public double hp,maxHp,cooldown=.3; public boolean charged;
        Tower(String type,double hp){this.type=type;this.hp=hp;maxHp=hp;}
    }
    public static final class Plot extends Point { public int id; public double facing; public Tower tower; Plot(int id,double radius,double facing){super(HOME.x+radius*Math.cos(facing),HOME.y+radius*Math.sin(facing));this.id=id;this.facing=facing;} }
    public static final class Enemy extends Point { public int id; public double hp,maxHp,speed; public boolean boss,armored; double hit; Enemy(int id,double angle,double hp,double speed,boolean boss,boolean armored){super(HOME.x+SPAWN_RADIUS*Math.cos(angle),HOME.y+SPAWN_RADIUS*Math.sin(angle));this.id=id;this.hp=hp;maxHp=hp;this.speed=speed;this.boss=boss;this.armored=armored;} }
    static final class Volley { final Set<Integer> hit=new HashSet<>(); }
    public static final class Projectile extends Point {
        public int id; public double angle; public boolean splash;
        double remaining,damage; final Volley volley;
        Projectile(int id,Plot source,double angle,double damage,boolean splash,Volley volley){super(source.x,source.y);this.id=id;this.angle=angle;this.damage=damage;this.splash=splash;this.volley=volley;remaining=TYPES.get("cannon").range;}
    }
    public static final class Drop extends Point { public int id,value; Drop(int id,double x,double y,int value){super(x,y);this.id=id;this.value=value;} }
    public static final class Effect extends Point { public int id; public String type,text; public double ttl=.8,tx,ty; Effect(int id,String type,double x,double y,String text,double tx,double ty){super(x,y);this.id=id;this.type=type;this.text=text;this.tx=tx;this.ty=ty;} }
    final String code; final boolean practice; final Random random;
    final List<Player> players=new ArrayList<>(); final List<Plot> plots=new ArrayList<>(); final List<Enemy> enemies=new ArrayList<>(); final List<Drop> drops=new ArrayList<>(); final List<Effect> effects=new ArrayList<>();
    final List<Projectile> projectiles=new ArrayList<>();
    final Map<String,Integer> charms=new LinkedHashMap<>(); final Map<String,String> votes=new HashMap<>(); List<Map<String,Object>> options=new ArrayList<>();
    String host,phase="lobby",notice="The farmer is asleep. Your watch begins soon.";
    double time,elapsed,wake,spawnTimer,harvestTimer; int wave,seeds,kills,id,squadSize=1;
    BigInteger score=BigInteger.ZERO; long lastSeen=System.currentTimeMillis();
    Game(String code,boolean practice){this(code,practice,new Random());}
    Game(String code,boolean practice,Random random){this.code=code;this.practice=practice;this.random=random;resetPlots();}
    void resetPlots(){plots.clear();for(int i=0;i<8;i++)plots.add(new Plot(i,190,i*Math.PI/4));for(int i=0;i<4;i++)plots.add(new Plot(8+i,110,Math.PI/4+i*Math.PI/2));}
    static boolean inFan(Plot p,Point target){return (target.x-p.x)*Math.cos(p.facing)+(target.y-p.y)*Math.sin(p.facing)>=-1e-8;}
    void fireFan(Plot plot,double damage,boolean splash){Volley volley=new Volley();for(int i=0;i<FAN_PELLETS;i++)projectiles.add(new Projectile(++id,plot,plot.facing-Math.PI/2+i*Math.PI/(FAN_PELLETS-1),damage,splash,volley));}
    void moveProjectiles(double dt){
        for(Iterator<Projectile> it=projectiles.iterator();it.hasNext();){
            Projectile p=it.next();double step=Math.min(p.remaining,300*dt),dx=Math.cos(p.angle),dy=Math.sin(p.angle),nearest=Double.POSITIVE_INFINITY;Enemy hit=null;
            for(Enemy e:enemies){if(e.hp<=0)continue;double ex=e.x-p.x,ey=e.y-p.y,along=ex*dx+ey*dy,r=e.boss?25:15,cross=ex*dy-ey*dx;
                if(Math.abs(cross)>r)continue;double half=Math.sqrt(r*r-cross*cross),entry=along-half;
                if(along+half>=0&&entry<=step&&Math.max(0,entry)<nearest){nearest=Math.max(0,entry);hit=e;}
            }
            if(hit!=null){
                p.x+=dx*nearest;p.y+=dy*nearest;
                if(p.volley.hit.add(hit.id))damage(hit,p.damage,p);
                if(p.splash){effect("splash",hit,"BIG HARVEST");for(Enemy e:enemies)if(e.hp>0&&dist(e,hit)<85&&p.volley.hit.add(e.id))damage(e,p.damage,p);}
                it.remove();
            }else{p.x+=dx*step;p.y+=dy*step;p.remaining-=step;if(p.remaining<=0)it.remove();}
        }
    }
    Player player(String id){return players.stream().filter(p->p.id.equals(id)).findFirst().orElse(null);}
    Player addPlayer(String id,String name,String skin){
        require(players.size()<MAX_PLAYERS,"This patch is full (8 players)."); require(phase.equals("lobby"),"This night has started. Join after the rematch.");
        name=name.strip().replaceAll("[\\p{Cntrl}]","");if(name.isEmpty())name="Little sprout";if(name.length()>18)name=name.substring(0,18);
        Player p=new Player(id,name,skin.equals("scarecrow")?skin:"pumpkin",players.size());players.add(p);if(host==null)host=id;return p;
    }
    boolean active(){return Set.of("dusk","wave","vote").contains(phase);}
    static void require(boolean b,String message){if(!b)throw new IllegalArgumentException(message);}
    static double dist(Point a,Point b){return Math.hypot(a.x-b.x,a.y-b.y);}
    static double clamp(double n,double lo,double hi){return Math.max(lo,Math.min(hi,n));}
    static String str(Map<String,Object>d,String k,String fallback){return d.get(k) instanceof String s?s:fallback;}
    static double num(Map<String,Object>d,String k){return d.get(k) instanceof Number n && Double.isFinite(n.doubleValue())?n.doubleValue():0;}
    int stack(String charm){return charms.getOrDefault(charm,0);}
    int upgradeCost(Tower t){return (int)Math.ceil(TYPES.get(t.type).cost*.75*Math.pow(1.55,t.level-1));}
    void command(String pid,Map<String,Object>d){
        Player p=player(pid); require(p!=null,"Rejoin your room to play."); String type=str(d,"type","");
        if(type.equals("ready")&&phase.equals("lobby")){p.ready=Boolean.TRUE.equals(d.get("ready"));return;}
        if(type.equals("start")){
            require(pid.equals(host)&&phase.equals("lobby"),"Only the host can start from the lobby.");
            List<Player> connected=players.stream().filter(q->q.connected).toList();
            require(connected.size()>=(practice?1:2),"Gather at least two players, or start a practice patch.");
            require(connected.stream().allMatch(q->q.ready),"Everyone needs to ready up first.");
            squadSize=connected.size();seeds=160+30*squadSize;phase="dusk";time=DUSK;harvestTimer=8;notice="Dusk · Guard the farmhouse from every direction.";return;
        }
        if(type.equals("rematch")){
            require(pid.equals(host)&&Set.of("won","lost").contains(phase),"The host can plant a new patch after this night.");
            phase="lobby";time=elapsed=wake=spawnTimer=harvestTimer=0;wave=seeds=kills=0;score=BigInteger.ZERO;enemies.clear();projectiles.clear();drops.clear();effects.clear();charms.clear();votes.clear();options.clear();resetPlots();
            players.removeIf(q->!q.connected);for(Player q:players){q.ready=false;q.hp=100;q.ghost=q.repair=q.collected=0;q.x=556;q.y=300;q.ix=q.iy=0;q.action=false;q.target=null;}
            notice="A fresh patch. A brand-new night.";return;
        }
        if(!active())return;
        if(type.equals("input")){p.ix=clamp(num(d,"x"),-1,1);p.iy=clamp(num(d,"y"),-1,1);p.action=Boolean.TRUE.equals(d.get("action"));p.inputAt=System.currentTimeMillis();if(p.ix!=0||p.iy!=0)p.target=null;return;}
        if(type.equals("move")){require(d.get("x") instanceof Number&&d.get("y") instanceof Number,"Invalid destination.");p.target=new Point(clamp(num(d,"x"),24,936),clamp(num(d,"y"),24,576));return;}
        if(type.equals("vote")&&phase.equals("vote")){String charm=str(d,"charm","");require(options.stream().anyMatch(c->c.get("id").equals(charm)),"Choose one of this harvest's charms.");votes.put(pid,charm);return;}
        if(!Set.of("build","upgrade").contains(type))return;
        require(p.ghost<=0,"Your pumpkin body will be back in a moment.");
        Plot plot=plots.stream().filter(q->q.id==num(d,"plot")).findFirst().orElse(null);
        require(plot!=null&&dist(p,plot)<=88,"Walk closer to that garden plot first.");
        if(type.equals("build")){
            String build=str(d,"build","");Spec spec=TYPES.get(build);require(spec!=null&&plot.tower==null,"Choose an empty plot and a defense.");require(seeds>=spec.cost,"Gather a few more moonseeds.");
            seeds-=spec.cost;plot.tower=new Tower(build,spec.hp*(build.equals("fence")?1+.5*stack("thorn"):1));effect("build",plot,"+ planted");
        }else{
            Tower t=plot.tower;require(t!=null,"Plant a defense here first.");require(t.level<10,"This defense reached level 10 for this night.");int cost=upgradeCost(t);require(seeds>=cost,"An upgrade needs "+cost+" moonseeds.");seeds-=cost;t.level++;t.maxHp*=1.35;t.hp=t.maxHp;effect("build",plot,"LEVEL "+t.level);
        }
    }
    void effect(String type,Point p,String text){effects.add(new Effect(++id,type,p.x,p.y,text,p.x,p.y));}
    void spawn(boolean boss){double angle=random.nextDouble()*Math.PI*2;boolean armored=!boss&&wave>=3&&random.nextDouble()<.23;double hp=(boss?750:armored?95:48)*Math.pow(1.48,wave-1)*(1+.18*(squadSize-1));enemies.add(new Enemy(++id,angle,hp,boss?7:armored?9:13+wave*1.8,boss,armored));}
    Drop gardenDrop(){double a=random.nextInt(4)*Math.PI/2;return new Drop(++id,HOME.x+140*Math.cos(a),HOME.y+140*Math.sin(a),12);}
    void startWave(){wave++;phase="wave";time=WAVE;spawnTimer=1;notice=wave==5?"The Skeleton King has arrived. Mind his crown.":"Wave "+wave+" · Keep the farmer dreaming.";if(wave==5)spawn(true);}
    void damage(Enemy e,double amount,Point from){
        if(e.hp<=0)return;e.hp-=amount;effects.add(new Effect(++id,"shot",from.x,from.y,Long.toString(Math.round(amount)),e.x,e.y));
        if(e.hp<=0){kills++;score=score.add(BigInteger.valueOf(e.boss?2100:100));drops.add(new Drop(++id,e.x,e.y+12,e.boss?80:8+wave*2));effect("poof",e,e.boss?"+2,100":"+100");}
    }
    void nextPhase(){
        if(phase.equals("dusk")||phase.equals("vote")){
            if(phase.equals("vote")){
                int max=-1;List<String> tied=new ArrayList<>();
                for(var c:options){String cid=(String)c.get("id");int count=(int)votes.values().stream().filter(cid::equals).count();if(count>max){max=count;tied.clear();}if(count==max)tied.add(cid);}
                String chosen=tied.get(random.nextInt(tied.size()));charms.merge(chosen,1,Integer::sum);
                if(chosen.equals("thorn"))for(Plot q:plots)if(q.tower!=null&&q.tower.type.equals("fence")){double bonus=TYPES.get("fence").hp*.5*Math.pow(1.35,q.tower.level-1);q.tower.maxHp+=bonus;q.tower.hp+=bonus;}
                options.clear();votes.clear();
            }startWave();
        }else if(phase.equals("wave")&&enemies.isEmpty()){
            projectiles.clear();score=score.add(BigInteger.valueOf(500));
            if(wave==WAVES){score=score.add(BigInteger.valueOf((long)Math.floor(Math.max(0,100-wake))*100));phase="won";notice="Sunrise! The farmer never suspected a thing.";}
            else{phase="vote";time=VOTE;votes.clear();List<Map<String,Object>> pool=new ArrayList<>(CHARMS);Collections.shuffle(pool,random);options=new ArrayList<>(pool.subList(0,3));notice="A little harvest magic · Vote, collect seeds, and repair.";}
        }
    }
    void tick(double dt){
        if(!active())return;elapsed+=dt;time=Math.max(0,time-dt);effects.removeIf(e->(e.ttl-=dt)<=0);
        for(Player p:players){
            if(!p.connected)continue;
            if(p.ghost>0){p.ghost=Math.max(0,p.ghost-dt);if(p.ghost==0){p.hp=100;p.x=556;p.y=300;}continue;}
            if(System.currentTimeMillis()-p.inputAt>600){p.ix=p.iy=0;p.action=false;}
            double x=p.ix,y=p.iy;
            if(p.target!=null&&x==0&&y==0){x=p.target.x-p.x;y=p.target.y-p.y;if(Math.hypot(x,y)<5){p.target=null;x=y=0;}}
            double len=Math.hypot(x,y);if(len>0){double step=Math.min(120*dt,p.target!=null?len:Double.MAX_VALUE);p.x=clamp(p.x+x/len*step,24,936);p.y=clamp(p.y+y/len*step,24,576);}
            p.hp=Math.min(100,p.hp+dt*2);
            if(p.action){Plot near=plots.stream().filter(q->q.tower!=null&&q.tower.hp<q.tower.maxHp&&dist(p,q)<88).min(Comparator.comparingDouble(q->dist(p,q))).orElse(null);if(near!=null){Tower t=near.tower;double heal=Math.min(t.maxHp-t.hp,dt*42);t.hp+=heal;p.repair+=heal;if(stack("patch")>0)t.charged=true;}}
            for(Iterator<Drop> it=drops.iterator();it.hasNext();){Drop d=it.next();if(dist(p,d)<48){seeds+=d.value;p.collected+=d.value;effect("seed",p,"+"+d.value);it.remove();}}
        }
        harvestTimer-=dt;if(harvestTimer<=0){harvestTimer=8;if(drops.size()<100)drops.add(gardenDrop());}
        if(phase.equals("wave")){spawnTimer-=dt;if(time>0&&spawnTimer<=0){spawn(false);spawnTimer=Math.max(.9,7/(1+.33*(squadSize-1))/(1+.2*(wave-1)));}}
        for(Plot plot:plots){
            Tower t=plot.tower;if(t==null)continue;t.cooldown-=dt;if(t.cooldown>0)continue;Spec spec=TYPES.get(t.type);
            if(t.type.equals("flower")){if(drops.size()<120)drops.add(new Drop(++id,plot.x,plot.y+36,(int)Math.round(6*Math.pow(1.4,t.level-1)*(1+.5*stack("moon")))));t.cooldown=spec.cooldown;continue;}
            Enemy target=enemies.stream().filter(e->e.hp>0&&dist(e,plot)<=spec.range&&(!t.type.equals("cannon")||inFan(plot,e))).min(Comparator.comparingDouble(e->dist(e,HOME))).orElse(null);if(target==null)continue;
            long nearby=plots.stream().filter(q->q!=plot&&q.tower!=null&&q.tower.type.equals("lantern")&&dist(q,plot)<180).count();
            t.cooldown=spec.cooldown/(t.type.equals("lantern")?1+.25*nearby*stack("candle"):1);t.shots++;
            double amount=spec.damage*Math.pow(1.65,t.level-1)*Math.pow(1.4,stack("ember"));if(t.type.equals("fence"))amount*=Math.pow(2,stack("thorn"));if(t.charged){amount*=1+2*stack("patch");t.charged=false;}
            boolean splash=t.type.equals("cannon")&&stack("harvest")>0&&t.shots%5==0;if(splash)amount*=1+2*stack("harvest");if(t.type.equals("cannon")){fireFan(plot,amount,splash);continue;}damage(target,amount,plot);
            if(splash){effect("splash",target,"BIG HARVEST");for(Enemy e:enemies)if(e!=target&&dist(e,target)<85)damage(e,amount,target);}
        }
        moveProjectiles(dt);
        for(Enemy e:enemies){
            if(e.hp<=0)continue;e.hit-=dt;
            Plot block=plots.stream().filter(q->q.tower!=null&&dist(q,e)<(q.tower.type.equals("fence")?48:30)).findFirst().orElse(null);
            Player lure=players.stream().filter(p->p.connected&&p.ghost<=0&&dist(p,e)<56).findFirst().orElse(null);
            if(block!=null){if(e.hit<=0){block.tower.hp-=(e.boss?38:13)*(1+wave*.15);e.hit=1;if(block.tower.hp<=0){effect("poof",block,"oh no!");block.tower=null;}}}
            else if(lure!=null){if(e.hit<=0){lure.hp-=e.boss?48:24;e.hit=1;if(lure.hp<=0){lure.ghost=5;lure.target=null;lure.ix=lure.iy=0;lure.action=false;}}}
            else {double distance=dist(e,HOME),step=Math.min(e.speed*dt,distance);if(distance>0){e.x+=(HOME.x-e.x)/distance*step;e.y+=(HOME.y-e.y)/distance*step;}}
            if(dist(e,HOME)<=HOME_RADIUS){wake=e.boss?100:Math.min(100,wake+(e.armored?9:5));e.hp=0;effect("wake",HOME,"shhh!");}
        }
        enemies.removeIf(e->e.hp<=0);
        if(wake>=100){phase="lost";notice="A sleepy farmer stirs… A new night is another chance.";}else if(time<=0)nextPhase();
    }
    Map<String,Object> snapshot(){return obj("code",code,"practice",practice,"host",host,"phase",phase,"time",time,"wave",wave,"seeds",seeds,"wake",wake,"score",score.toString(),"kills",kills,"elapsed",elapsed,"squadSize",squadSize,"notice",notice,"players",players,"plots",plots,"enemies",enemies,"projectiles",projectiles,"drops",drops,"effects",effects,"charms",charms,"options",options,"votes",votes);}
}

