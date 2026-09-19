package com.iridium126.createmanaindustry.dimension.gen;

import com.iridium126.createmanaindustry.dimension.gen.markov.MarkovModel;
import java.util.*;
import java.util.function.DoubleFunction;
import net.minecraft.core.Direction;

/** Immutable, sparsely paged architecture compiled from a genuine three-dimensional MJ program. */
public final class SanctuaryNetwork {
    public static final int X = 7, Z = 60, Y = 7;
    public static final int NONE=0, CLEAR=1, PATH=2, PATH_SLAB=3, DECK=4, DECK_SLAB=5,
        TIMBER=6, ROPE=7, LIGHT=8, ROOT_BARK=9, ROOT_CORE=10, ROOT_MOSS=11, SUPPORT=12;
    private static final MarkovModel MODEL = load();
    private final Map<Long, byte[]> pages = new HashMap<>();
    private final Set<Long> clearances = new HashSet<>();
    private final List<Route> routes = new ArrayList<>();
    private final List<Root> roots = new ArrayList<>();
    private final List<Point> geodes = new ArrayList<>();
    private final Set<Long> verticalRopes = new HashSet<>();
    private final Map<Long, Direction> horizontalRopes = new HashMap<>();
    private final Map<Long, Integer> verticalRopeConnections = new HashMap<>();
    // Route identity lets a vertical overlap move the whole connected surface,
    // instead of removing one cell from a bridge or gallery junction.
    private final Map<Long,Integer> slabOwners = new HashMap<>();
    private final long seed;
    private final AllvrSanctuary terrain;
    private final double rotation;
    private final byte[] modules;

    public record Point(double x, double y, double z) {
        Point add(double dx, double dy, double dz) { return new Point(x+dx,y+dy,z+dz); }
        static Point lerp(Point a, Point b, double t) {
            return new Point(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t);
        }
    }
    public record Route(String kind, List<Point> points, double width) {}
    public record Root(List<Point> points, double initialRadius, double finalRadius) {}
    public List<Route> routes() { return Collections.unmodifiableList(routes); }
    public List<Root> roots() { return Collections.unmodifiableList(roots); }
    public List<Point> geodes() { return Collections.unmodifiableList(geodes); }
    public boolean isVerticalRope(int x, int y, int z) { return verticalRopes.contains(position(x, y, z)); }
    public Direction ropeConnectionDirection(int x, int y, int z) { return horizontalRopes.get(position(x, y, z)); }
    public boolean verticalRopeConnects(int x, int y, int z, Direction direction) {
        return (verticalRopeConnections.getOrDefault(position(x, y, z), 0) & directionBit(direction)) != 0;
    }

    public SanctuaryNetwork(long seed) {
        this.seed = seed;
        terrain = new AllvrSanctuary(seed);
        rotation = AllvrSanctuary.unit(seed) * Math.PI * 2;
        modules = MODEL.generate((int)(seed ^ seed >>> 32), 4000);
        // Roots first; the later gallery clearances deliberately bore through their shoulders.
        for (int station=0; station<10; station++) growRoot(station);
        for (int side=0; side<2; side++) for (int layer=0; layer<3; layer++) {
            final int s=side,l=layer;
            for(int row=0;row<Z;row++) {
                int radial=side==0?0:6,level=1+2*layer;
                if(modules[radial+row*X+level*X*Z]==0 || modules[radial+((row+1)%Z)*X+level*X*Z]==0) continue;
                final int start=row;
                addRoute("wall",t -> wall(s,l,(start+t)*Math.PI*2/Z),2.4,false,true);
            }
        }
        for (int layer=0; layer<Y; layer++) for (int row=0; row<Z; row++) for (int x=0; x<X; x++) {
            char module = MODEL.values().charAt(modules[x+row*X+layer*X*Z]);
            double angle=row*Math.PI*2/Z;
            int station=row/6;
            if ("ADEF".indexOf(module)>=0) {
                int side=x==0?0:1, lower=(layer-2)/2;
                addRoute("ramp", t -> {
                    double a=angle+1.45*t;
                    Point low=wall(side,lower,a), high=wall(side,lower+1,a);
                    Point p=Point.lerp(low,high,AllvrSanctuary.smooth(t));
                    // Move clear of the gallery before gaining height, preserving headroom at junctions.
                    double inward=(side==0?-1:1)*12*Math.pow(Math.max(0,Math.sin(t*Math.PI)),.35);
                    return p.add(inward*Math.cos(a+rotation),0,inward*Math.sin(a+rotation));
                }, 1.65, false, true);
            }
            if (module=='H') {
                Point a=wall(0,1,angle),b=wall(1,1,angle+.08);
                suspension(a,b,station%3==0?3:2,7,"wall-bridge");
            } else if (module=='R') {
                Point a=rootWalk(station,.40),b=rootWalk((station+1)%10,.40);
                suspension(a,b,1.7,4,"root-bridge");
            } else if (module=='P') {
                Point p=wall(0,2,angle);
                addRoute("lookout",t -> p.add(Math.cos(angle+rotation)*t*9,0,Math.sin(angle+rotation)*t*9),3.4,false,true);
            }
        }
        for (int station=0; station<10; station++) {
            double angle=station*Math.PI/5;
            Point rim=polar(219,96,angle-.36),end=wall(1,2,angle);
            addRoute("entrance",t -> {
                double a=angle-.36+.36*t;
                return polar(219+(Math.hypot(end.x,end.z)-219)*t,96+(end.y-96)*AllvrSanctuary.smooth(t),a);
            },2.3,false,true);
            final int i=station;
            // Reach the root through the inner wall, then alternate covered and exposed root shoulders.
            Point join=wall(0,0,angle-.75);
            Point rootStart=rootWalk(i,.12);
            addRoute("root-access",t -> recessedJoin(join,rootStart,t,-25),1.6,false,true);
            addRoute("root-path",t -> rootWalk(i,.12+.80*t),1.5,false,false);
            Point tail=rootWalk(i,.92);
            Point exit=wall(1,0,angle+rootTwist(i)*.92+.40);
            addRoute("root-exit",t -> recessedJoin(tail,exit,t,25),1.5,false,true);
        }
        // Routes can overlap in either stamping order.  A lower route may be
        // written after the upper slab has already been emitted, so normalize
        // the finished voxel graph once more before carving headroom.
        mergeAdjacentBottomSlabs();
        // Amethyst geodes are independent valley-floor landmarks.  They deliberately
        // do not consume a Markov module or turn into player-facing side branches.
        placeBottomGeodes();
        // All routing is complete before carving headroom; cables/foliage cannot close the main paths.
        for (long pos : clearances) {
            int x = (int) (pos >> 40), y = (int) ((pos >> 20) & 0xfffff) - 512, z = (int) (pos & 0xfffff) - 512;
            int material = get(x, y, z);
            if (material < PATH || material > TIMBER)
                put(x, y, z, CLEAR);
        }
        clearances.clear();
        promoteVerticalRopeChains();
        calculateVerticalRopeConnections();
    }

    /** Promote every contiguous rope directly above a vertical hanger. */
    private void promoteVerticalRopeChains() {
        for(long key:new ArrayList<>(verticalRopes)) {
            int x=(int)(key>>40), y=(int)(((key>>20)&0xfffff)-512), z=(int)((key&0xfffff)-512);
            for(int aboveY=y+1;;aboveY++) {
                if(get(x,aboveY,z)!=ROPE) break;
                long above=position(x,aboveY,z);
                verticalRopes.add(above);
                horizontalRopes.remove(above);
            }
        }
    }

    private void calculateVerticalRopeConnections() {
        // Resolve the fence shape from the complete voxel graph before chunks are emitted;
        // this keeps connection states identical regardless of chunk generation order.
        for(long key:verticalRopes) {
            int x=(int)(key>>40), y=(int)(((key>>20)&0xfffff)-512), z=(int)((key&0xfffff)-512);
            int mask=0;
            for(Direction direction:Direction.Plane.HORIZONTAL) {
                int nx=x+direction.getStepX(), nz=z+direction.getStepZ(), material=get(nx,y,nz);
                boolean connects;
                if(material==ROPE) {
                    if(verticalRopes.contains(position(nx,y,nz))) connects=true;
                    else {
                        Direction extension=horizontalRopes.get(position(nx,y,nz));
                        connects=extension!=null && extension.getAxis()==direction.getAxis();
                    }
                } else if(material==NONE) {
                    var column=terrain.column(nx,nz);
                    connects=y<=95 && !terrain.cavity(column,y);
                } else {
                    connects=material!=CLEAR && material!=PATH_SLAB && material!=DECK_SLAB;
                }
                if(connects) mask|=directionBit(direction);
            }
            if(mask!=0) verticalRopeConnections.put(key,mask);
        }
    }

    private static int directionBit(Direction direction) {
        return switch(direction) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 4;
            case WEST -> 8;
            default -> 0;
        };
    }

    private void placeBottomGeodes() {
        int desired = 1 + (int)(AllvrSanctuary.unit(terrain.hash(913, -41, (int)(seed ^ (seed >>> 32)))) * 3.0);
        int accepted = 0;
        for (int attempt = 0; attempt < 192 && accepted < desired; attempt++) {
            long h = terrain.hash(37 * attempt + 11, -777, (int)(seed + attempt * 131L));
            double angle = AllvrSanctuary.unit(h) * Math.PI * 2.0 + rotation;
            double radius = 120.0 + AllvrSanctuary.unit(terrain.hash(attempt, -778, (int)(h >>> 32))) * 62.0;
            int x = (int)Math.round(radius * Math.cos(angle));
            int z = (int)Math.round(radius * Math.sin(angle));
            // Keep the feature's positive sampling offsets in solid rock.  Its
            // upper shell then breaks naturally into the open valley floor.
            int y = terrain.column(x, z).floor() - 1;
            Point candidate = new Point(x, y, z);
            if (validGeodeCandidate(candidate, 16.0) && geodes.stream().noneMatch(p -> Math.hypot(p.x-x, p.z-z) < 44.0)) {
                geodes.add(candidate);
                accepted++;
            }
        }
        // A sparse fallback keeps the contract at 1..3 even for an unusually dense
        // routing seed, while retaining a broad, separated composition on the floor.
        for (int i = accepted; i < desired; i++) {
            double angle = rotation + (i + .5) * Math.PI * 2.0 / desired;
            double radius = 134.0 + i * 24.0;
            int x = (int)Math.round(radius * Math.cos(angle)), z = (int)Math.round(radius * Math.sin(angle));
            int y = terrain.column(x, z).floor() - 1;
            Point candidate = new Point(x, y, z);
            if (geodes.stream().noneMatch(p -> Math.hypot(p.x-x, p.z-z) < 44.0)) geodes.add(candidate);
        }
    }

    private boolean validGeodeCandidate(Point p, double radius) {
        double radial = Math.hypot(p.x, p.z);
        if (radial < 115.0 || radial > 185.0) return false;
        int cx = (int)Math.round(p.x), cy = (int)Math.round(p.y), cz = (int)Math.round(p.z);
        for (int dz = -16; dz <= 16; dz++) for (int dx = -16; dx <= 16; dx++) {
            if (dx * dx + dz * dz > radius * radius) continue;
            for (int dy = -10; dy <= 10; dy += 2) {
                int material = get(cx + dx, cy + dy, cz + dz);
                if (material != NONE && material != CLEAR) return false;
            }
        }
        return true;
    }

    private static MarkovModel load() {
        try(var xml=SanctuaryNetwork.class.getResourceAsStream("/data/createmanaindustry/markov/karst_causeways.xml")) {
            if(xml==null) throw new IllegalStateException("Missing sanctuary model");
            return MarkovModel.load(xml,X,Z,Y);
        } catch(java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private Point polar(double radius,double y,double angle) {
        return new Point(radius*Math.cos(angle+rotation),y,radius*Math.sin(angle+rotation));
    }
    private static Point recessedJoin(Point a,Point b,double t,double recess) {
        double angleA=Math.atan2(a.z,a.x),delta=Math.atan2(Math.sin(Math.atan2(b.z,b.x)-angleA),Math.cos(Math.atan2(b.z,b.x)-angleA));
        double angle=angleA+delta*t,r=Math.hypot(a.x,a.z)*(1-t)+Math.hypot(b.x,b.z)*t
            +recess*Math.pow(Math.max(0,Math.sin(t*Math.PI)),.35);
        return new Point(Math.cos(angle)*r,a.y+(b.y-a.y)*AllvrSanctuary.smooth(t),Math.sin(angle)*r);
    }
    private Point wall(int side,int layer,double angle) {
        double r=(side==0?101:199)+5.5*Math.sin(3*angle+rotation+layer*.8)
            +2*Math.sin(angle*7+layer);
        double y=17+29*layer+3*Math.sin(angle*2+rotation)+1.5*Math.cos(angle*5+layer);
        return polar(r,y,angle);
    }
    private double rootTwist(int i) { return .11*Math.sin(i*2.4+rotation)+.04; }
    public Point rootPoint(int i,double t) {
        double phase=rotation+i*2.399963, envelope=Math.sin(t*Math.PI);
        double meander=envelope*(.070*Math.sin(t*Math.PI*2+phase)+.030*Math.sin(t*Math.PI*5+phase*.7));
        double radius=81+141*t+4*envelope*Math.sin(t*Math.PI*2+phase);
        double height=34-35*t+1.5*envelope+1.1*envelope*Math.sin(t*Math.PI*3+phase);
        return polar(radius,height,i*Math.PI/5+rootTwist(i)*t+meander);
    }
    private static double rootRadius(double t) { return 8.2*Math.pow(1-t,.75)+1.2; }
    private Point rootWalk(int i,double t) {
        double u=(t-.12)/.80;
        double shoulder=rootRadius(t)*.72+.5-3.5*(1-AllvrSanctuary.smooth((t-.12)/.32))
            -2.5*Math.pow(Math.max(0,Math.sin(u*Math.PI*2)),4);
        return rootPoint(i,t).add(0,shoulder,0);
    }

    private void growRoot(int i) {
        var points=new ArrayList<Point>();
        for(int step=0;step<=282;step++) {
            double t=step/282.0; Point p=rootPoint(i,t); points.add(p);
            double radius=rootRadius(t);
            for(int z=(int)Math.floor(p.z-radius-1);z<=p.z+radius+1;z++)
            for(int x=(int)Math.floor(p.x-radius-1);x<=p.x+radius+1;x++)
            for(int y=(int)Math.floor(p.y-radius-1);y<=p.y+radius+1;y++) {
                double d=Math.sqrt((x-p.x)*(x-p.x)+Math.pow((y-p.y)/.72,2)+(z-p.z)*(z-p.z));
                double angle=Math.atan2((y-p.y)/.72,(x-p.x)*Math.sin(i*Math.PI/5+rotation)-(z-p.z)*Math.cos(i*Math.PI/5+rotation));
                double rough=.6*Math.sin(angle*5+1.5*Math.sin(t*9+i))+.22*Math.sin(x*.57+z*.37);
                if(d<=radius+rough) {
                    int material=d<radius-1.4?ROOT_CORE:ROOT_BARK;
                    if(material==ROOT_BARK && y>p.y+radius*.55 && (terrain.hash(x,y,z)&31)==0) material=ROOT_MOSS;
                    if(get(x,y,z)!=ROOT_CORE) put(x,y,z,material);
                }
            }
        }
        roots.add(new Root(List.copyOf(points),rootRadius(0),rootRadius(1)));
        // TallRainforestTree-inspired stages: flare, shifting scaffold, outward buds, tapered fine roots.
        // Lateral branches leave at acute angles and terminate in soil; their starts overlap the parent.
        for(int branch=0;branch<4;branch++) {
            double t=.26+branch*.18,sign=((i+branch)&1)==0?1:-1;
            Point start=rootPoint(i,t),along=rootPoint(i,Math.min(1,t+.12));
            Point tip=polar(177+branch*16,-7-(branch%2)*3,i*Math.PI/5+rootTwist(i)+sign*(.13+.04*branch));
            Point control1=Point.lerp(start,along,.9).add(0,-2,0);
            Point control2=Point.lerp(along,tip,.72).add(sign*5,2,-sign*4);
            final double initial=rootRadius(t)*.48;
            Point previous=start;
            for(int step=0;step<=160;step++) {
                double u=step/160.0;Point p=bezier(start,control1,control2,tip,u);
                double radius=initial*Math.pow(1-u,.85)+.35;
                sphere(p,radius,ROOT_BARK);
                if(radius<1.2) rootFiber(previous,p);
                previous=p;
            }
            Point fork=bezier(start,control1,control2,tip,.62);
            Point fineEnd=tip.add(sign*11,-2,sign*7);
            previous=fork;
            for(int step=0;step<=100;step++) {
                double u=step/100.0;Point p=Point.lerp(fork,fineEnd,u).add(sign*3*Math.sin(u*Math.PI),0,0);
                sphere(p,1.35*(1-u)+.3,ROOT_BARK);
                rootFiber(previous,p);previous=p;
            }
        }
    }
    private static Point bezier(Point a,Point b,Point c,Point d,double t) {
        return Point.lerp(Point.lerp(Point.lerp(a,b,t),Point.lerp(b,c,t),t),
            Point.lerp(Point.lerp(b,c,t),Point.lerp(c,d,t),t),t);
    }
    private void rootFiber(Point a,Point b) {
        int x=(int)Math.round(a.x),y=(int)Math.round(a.y),z=(int)Math.round(a.z);
        int tx=(int)Math.round(b.x),ty=(int)Math.round(b.y),tz=(int)Math.round(b.z);
        // Digital elbows keep sub-block tips face-connected, as in TallRainforestTree's scaffold rules.
        put(x,y,z,ROOT_BARK);
        while(x!=tx) { x+=Integer.signum(tx-x);put(x,y,z,ROOT_BARK); }
        while(y!=ty) { y+=Integer.signum(ty-y);put(x,y,z,ROOT_BARK); }
        while(z!=tz) { z+=Integer.signum(tz-z);put(x,y,z,ROOT_BARK); }
    }

    private void sphere(Point p,double radius,int material) {
        for(int z=(int)Math.floor(p.z-radius);z<=p.z+radius;z++)
        for(int x=(int)Math.floor(p.x-radius);x<=p.x+radius;x++)
        for(int y=(int)Math.floor(p.y-radius);y<=p.y+radius;y++)
            if((x-p.x)*(x-p.x)+(y-p.y)*(y-p.y)+(z-p.z)*(z-p.z)<=radius*radius) put(x,y,z,material);
    }

    private void suspension(Point a,Point b,double width,double sag,String kind) {
        DoubleFunction<Point> curve=t -> Point.lerp(a,b,t).add(0,-4*sag*t*(1-t),0);
        addRoute(kind,curve,width,true,false);
        double length=Math.hypot(b.x-a.x,b.z-a.z),nx=-(b.z-a.z)/length,nz=(b.x-a.x)/length;
        Direction connectionDirection=horizontalDirection(a,b);
        for(int side:new int[]{-1,1}) {
            // Tall timber anchor towers, a catenary-like cable and regularly spaced hangers.
            for(Point end:List.of(a,b)) for(int y=(int)end.y-3;y<=end.y+7;y++)
                put((int)Math.round(end.x+nx*side*(width+.8)),y,(int)Math.round(end.z+nz*side*(width+.8)),TIMBER);
            if(kind.equals("wall-bridge")) for(Point end:List.of(a,b)) {
                Point tower=end.add(nx*side*(width+.8),0,nz*side*(width+.8));
                double r=Math.hypot(tower.x,tower.z),anchor=r<150?88:216;
                Point rock=new Point(tower.x/r*anchor,end.y-5,tower.z/r*anchor);
                for(int k=0;k<=80;k++) {
                    double t=k/80.0;
                    sphere(Point.lerp(tower.add(0,-2,0),rock,t),.9,TIMBER);
                    Point cable=Point.lerp(tower.add(0,7,0),rock.add(0,10,0),t);
                    putHorizontalRope((int)Math.round(cable.x),(int)Math.round(cable.y),(int)Math.round(cable.z),connectionDirection);
                }
            }
            for(int step=0;step<=length*3;step++) {
                double t=step/(length*3),offset=side*(width+.8);
                Point deck=curve.apply(t),cable=Point.lerp(a,b,t).add(nx*offset,7-4*(sag+4)*t*(1-t),nz*offset);
                int x=(int)Math.round(cable.x),z=(int)Math.round(cable.z),y=(int)Math.round(cable.y);
                if(step%45==0) put(x,y,z,LIGHT);
                else putHorizontalRope(x,y,z,connectionDirection);
                if(step%15==0) for(int hy=(int)Math.ceil(deck.y);hy<=y;hy++) {
                    // Keep the periodic light marker at cable height, but make
                    // every actual rope voxel in the hanger's vertical run own
                    // the vertical-rope priority, including the former top gate.
                    if(get(x,hy,z)!=LIGHT) putVerticalRope(x,hy,z);
                }
                // Lower hand rope stays about 1.5 blocks above the walking surface.
                putHorizontalRope(x,(int)Math.floor(deck.y+1.5),z,connectionDirection);
            }
        }
    }

    private static Direction horizontalDirection(Point a, Point b) {
        double dx=b.x-a.x,dz=b.z-a.z;
        if (Math.abs(dx)>=Math.abs(dz)) return dx>=0 ? Direction.EAST : Direction.WEST;
        return dz>=0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void addRoute(String kind,DoubleFunction<Point> curve,double width,boolean wood,boolean wallSupport) {
        int owner=routes.size();
        var points=new ArrayList<Point>();points.add(curve.apply(0));
        for(int i=0;i<128;i++) subdivide(curve,i/128.0,curve.apply(i/128.0),(i+1)/128.0,curve.apply((i+1)/128.0),points,0);
        for(int step=0;step<points.size();step++) {
            Point p=points.get(step);
            double top=Math.round(p.y*2)/2.0;
            int floor=(int)Math.ceil(top)-1, material=wood?(top%1==0?DECK:DECK_SLAB):(top%1==0?PATH:PATH_SLAB);
            for(int z=(int)Math.floor(p.z-width);z<=p.z+width;z++) for(int x=(int)Math.floor(p.x-width);x<=p.x+width;x++) {
                double d=Math.hypot(x-p.x,z-p.z);
                if(d>width) continue;
                // Two adjacent bottom slabs leave a half-block void between their
                // collision shapes.  Treat a bottom slab below as the lower half
                // of the current step and fill that lower cell with its full block.
                int placedFloor=floor;
                int placedMaterial=mergeBottomSlab(material,get(x,floor-1,z));
                if(placedMaterial!=material) placedFloor--;
                put(x,placedFloor,z,placedMaterial);
                long placed=position(x,placedFloor,z);
                if(placedMaterial==PATH_SLAB || placedMaterial==DECK_SLAB) slabOwners.put(placed,owner);
                else slabOwners.remove(placed);
                if(!wood) put(x,placedFloor-1,z,SUPPORT);
                // Full width headroom is reserved independently of stamping order.
                for(int y=placedFloor+1;y<=placedFloor+4;y++) clearances.add(position(x,y,z));
            }
            if(wallSupport && step%14==0) {
                // Corbels slope back into the rock; no freestanding stone columns to the floor.
                double r=Math.hypot(p.x,p.z),target=r<150?89:214;
                for(int k=0;k<18;k++) {
                    double f=k/17.0;
                    Point q=new Point(p.x/r*(r+(target-r)*f),floor-2-5*f,p.z/r*(r+(target-r)*f));
                    sphere(q,1.15,SUPPORT);
                }
            }
        }
        routes.add(new Route(kind,List.copyOf(points),width));
    }

    /** Returns the lower slab's full-block symbol when two bottom slabs stack. */
    static int mergeBottomSlab(int material,int below) {
        if(material!=PATH_SLAB && material!=DECK_SLAB) return material;
        if(below==PATH_SLAB) return PATH;
        if(below==DECK_SLAB) return DECK;
        return material;
    }

    private void mergeAdjacentBottomSlabs() {
        var starts=new ArrayList<int[]>();
        forEach((x,y,z,m)-> {
            if(m!=PATH_SLAB && m!=DECK_SLAB) return;
            int above=get(x,y+1,z);
            if(above!=PATH_SLAB && above!=DECK_SLAB) return;
            Integer upperOwner=slabOwners.get(position(x,y+1,z));
            if(upperOwner!=null) starts.add(new int[]{x,y+1,z,upperOwner});
        });
        var shifted=new HashSet<Long>();
        for(int[] start:starts) shiftSlabComponent(start[0],start[1],start[2],start[3],shifted);
    }

    /** Move one connected route surface down as a unit, then clear its old layer. */
    private void shiftSlabComponent(int startX,int upperY,int startZ,int owner,Set<Long> shifted) {
        var queue=new ArrayDeque<int[]>();queue.add(new int[]{startX,upperY,startZ});
        while(!queue.isEmpty()) {
            int[] cell=queue.removeFirst();int x=cell[0],y=cell[1],z=cell[2];
            long key=position(x,y,z);
            if(!shifted.add(key) || !Objects.equals(slabOwners.get(key),owner)) continue;
            int slab=get(x,y,z);if(slab!=PATH_SLAB && slab!=DECK_SLAB) continue;
            int below=get(x,y-1,z),merged=mergeBottomSlab(slab,below);
            if(merged==slab && below!=NONE && below!=CLEAR) continue;
            put(x,y-1,z,merged==slab?slab:merged);
            if(slab==PATH_SLAB && merged==slab) put(x,y-2,z,SUPPORT);
            clearances.add(position(x,y,z));
            for(int clearanceY=y-1;clearanceY<=y+2;clearanceY++) clearances.add(position(x,clearanceY,z));
            put(x,y,z,CLEAR);
            slabOwners.remove(key);
            if(merged==PATH_SLAB || merged==DECK_SLAB) slabOwners.put(position(x,y-1,z),owner);
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                if(Objects.equals(slabOwners.get(position(x+d[0],y,z+d[1])),owner)) queue.add(new int[]{x+d[0],y,z+d[1]});
        }
    }

    private static void subdivide(DoubleFunction<Point> curve,double a,Point pa,double b,Point pb,List<Point> out,int depth) {
        double mid=(a+b)/2;Point pm=curve.apply(mid),linear=Point.lerp(pa,pb,.5);
        if(depth<20 && (Math.hypot(pb.x-pa.x,pb.z-pa.z)>.42 || Math.abs(pb.y-pa.y)>.25
                || Math.hypot(pm.x-linear.x,pm.z-linear.z)>.08)) {
            subdivide(curve,a,pa,mid,pm,out,depth+1);subdivide(curve,mid,pm,b,pb,out,depth+1);
        } else out.add(pb);
    }

    private static long position(int x,int y,int z) { return ((long)x<<40)|((long)(y+512)<<20)|(z+512); }
    private static long page(int x,int y,int z) { return position(x>>4,y>>4,z>>4); }
    private void put(int x,int y,int z,int value) {
        if(x < -250 || x > 250 || z < -250 || z > 250 || y < -16 || y > 110) return;
        byte[] data=pages.computeIfAbsent(page(x,y,z),k->new byte[4096]);
        int old=data[(x&15)|((z&15)<<4)|((y&15)<<8)];
        if(old>=PATH && old<=DECK_SLAB && value>DECK_SLAB) return;
        data[(x&15)|((z&15)<<4)|((y&15)<<8)]=(byte)value;
        if(value!=ROPE) {
            long key=position(x,y,z);
            verticalRopes.remove(key);
            horizontalRopes.remove(key);
        }
    }
    private void putVerticalRope(int x,int y,int z) {
        put(x,y,z,ROPE);
        if(get(x,y,z)==ROPE) {
            long key=position(x,y,z);
            verticalRopes.add(key);
            horizontalRopes.remove(key);
        }
    }
    private void putHorizontalRope(int x,int y,int z,Direction connectionDirection) {
        // A vertical hanger owns an intersection.  Keep its fence so the crossing
        // remains a continuous post instead of being replaced by a gate.
        long key=position(x,y,z);
        if(verticalRopes.contains(key)) return;
        put(x,y,z,ROPE);
        if(get(x,y,z)==ROPE) {
            verticalRopes.remove(key);
            horizontalRopes.put(key,connectionDirection);
        }
    }
    public int get(int x,int y,int z) {
        byte[] data=pages.get(page(x,y,z));
        return data==null?NONE:data[(x&15)|((z&15)<<4)|((y&15)<<8)];
    }
    public boolean hasChunk(int x,int z) {
        for(int y=-1;y<=6;y++) if(pages.containsKey(page(x<<4,y<<4,z<<4))) return true;
        return false;
    }
    @FunctionalInterface public interface Visitor { void accept(int x,int y,int z,int material); }
    public void forEach(Visitor visitor) {
        pages.forEach((key,data)->{
            int x0=(int)(key>>40)*16,y0=((int)((key>>20)&0xfffff)-512)*16,z0=((int)(key&0xfffff)-512)*16;
            for(int i=0;i<4096;i++) if(data[i]!=NONE) visitor.accept(x0+(i&15),y0+(i>>8),z0+((i>>4)&15),data[i]);
        });
    }
}
