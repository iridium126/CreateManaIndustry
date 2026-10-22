package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/** Immutable watershed geometry. Built only when group membership changes. */
public final class FuelTankGeometry {
    private static final Direction[] DIRECTIONS = Direction.values();
    public final BlockPos origin;
    public final BlockPos minCell;
    public final BlockPos maxCell;
    public final Map<BlockPos, Integer> basinByCell;
    public final List<Basin> basins;
    public final int perBlock;
    private final int[][] neighbors;
    private final int[][] spills;

    private FuelTankGeometry(Draft data, Set<BlockPos> cells, int perBlock) {
        this.perBlock = perBlock;
        BlockPos first = null;
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(BlockPos p: cells) {
            if(first == null || comparePos(p, first) < 0) first = p;
            minX=Math.min(minX,p.getX()); minY=Math.min(minY,p.getY()); minZ=Math.min(minZ,p.getZ());
            maxX=Math.max(maxX,p.getX()); maxY=Math.max(maxY,p.getY()); maxZ=Math.max(maxZ,p.getZ());
        }
        origin = first;
        minCell=new BlockPos(minX,minY,minZ); maxCell=new BlockPos(maxX,maxY,maxZ);
        basinByCell=Collections.unmodifiableMap(data.basinByCell);
        neighbors=new int[data.basins.size()][]; spills=new int[data.basins.size()][];
        for(DraftBasin basin:data.basins) {
            if(data.basins.size()==1) {
                neighbors[0]=new int[0]; spills[0]=new int[0]; basin.ridge=false; continue;
            }
            TreeMap<Integer,Integer> edges=new TreeMap<>();
            for(BlockPos p:basin.cells) for(Direction direction:DIRECTIONS) {
                BlockPos q=p.relative(direction); Integer other=basinByCell.get(q);
                if(other!=null && other!=basin.id)
                    edges.merge(other,Math.max(p.getY(),q.getY())-origin.getY(),Math::min);
            }
            neighbors[basin.id]=edges.keySet().stream().mapToInt(Integer::intValue).toArray();
            spills[basin.id]=edges.values().stream().mapToInt(Integer::intValue).toArray();
            if(neighbors[basin.id].length < 2) basin.ridge=false;
        }
        List<Basin> built=new ArrayList<>();
        for(DraftBasin basin:data.basins) built.add(new Basin(basin,origin.getY(),perBlock));
        basins=List.copyOf(built);
    }

    public int neighborCount(int basin) { return neighbors[basin].length; }
    public int neighbor(int basin,int edge) { return neighbors[basin][edge]; }
    public int spill(int basin,int edge) { return spills[basin][edge]; }
    public int edgeCount() { int count=0; for(int[] row:neighbors) count+=row.length; return count; }
    public long capacity() { return (long)basinByCell.size()*perBlock; }
    public int lowestBasin() {
        int best=0; for(int i=1;i<basins.size();i++) if(basins.get(i).minY<basins.get(best).minY) best=i;
        return best;
    }
    static int comparePos(BlockPos a, BlockPos b) {
        int c=Integer.compare(a.getY(),b.getY()); if(c!=0)return c;
        c=Integer.compare(a.getX(),b.getX()); return c!=0?c:Integer.compare(a.getZ(),b.getZ());
    }

    public CompoundTag write() {
        CompoundTag tag=new CompoundTag(); tag.putInt("PerBlock",perBlock);
        tag.putInt("BasinCount",basins.size());
        tag.putIntArray("Origin",new int[]{origin.getX(),origin.getY(),origin.getZ()});
        int[] coordinates=new int[basinByCell.size()*3]; int index=0;
        List<BlockPos> ordered=basinByCell.keySet().stream().sorted(FuelTankGeometry::comparePos).toList();
        for(BlockPos cell:ordered) {
            coordinates[index++]=cell.getX()-origin.getX(); coordinates[index++]=cell.getY()-origin.getY();
            coordinates[index++]=cell.getZ()-origin.getZ();
        }
        tag.putIntArray("Cells",coordinates);
        if(basins.size()>1 && basins.size()<=256) {
            byte[] ids=new byte[ordered.size()];for(int i=0;i<ids.length;i++)ids[i]=(byte)(int)basinByCell.get(ordered.get(i));
            tag.putByteArray("CellBasins8",ids);
        } else if(basins.size()>256) {
            int[] ids=new int[ordered.size()];for(int i=0;i<ids.length;i++)ids[i]=basinByCell.get(ordered.get(i));
            tag.putIntArray("CellBasins",ids);
        }
        BitSet ridges=new BitSet();for(Basin basin:basins)if(basin.ridge)ridges.set(basin.id);
        tag.putLongArray("Ridges",ridges.toLongArray());return tag;
    }
    public static FuelTankGeometry read(CompoundTag tag, BlockPos origin) {
        int[] coordinates=tag.getIntArray("Cells"); Set<BlockPos> cells=new HashSet<>();
        if(coordinates.length==0 || coordinates.length%3!=0) throw new IllegalArgumentException("Invalid fuel tank geometry");
        int count=tag.getInt("BasinCount"),perBlock=tag.getInt("PerBlock");
        if(count<1 || count>coordinates.length/3 || perBlock<=0)throw new IllegalArgumentException("Invalid fuel basin count");
        byte[] small=tag.getByteArray("CellBasins8");int[] large=tag.getIntArray("CellBasins");
        if(count>1 && (count<=256?small.length:large.length)!=coordinates.length/3)
            throw new IllegalArgumentException("Invalid fuel cell assignments");
        Draft data=new Draft();BitSet ridges=BitSet.valueOf(tag.getLongArray("Ridges"));
        for(int b=0;b<count;b++){DraftBasin basin=new DraftBasin();basin.id=b;basin.ridge=ridges.get(b);data.basins.add(basin);}
        for(int i=0;i<coordinates.length;i+=3) {
            BlockPos cell=origin.offset(coordinates[i],coordinates[i+1],coordinates[i+2]);
            int id=count==1?0:count<=256?Byte.toUnsignedInt(small[i/3]):large[i/3];
            if(id<0 || id>=count || !cells.add(cell))throw new IllegalArgumentException("Invalid fuel cell");
            DraftBasin basin=data.basins.get(id);basin.cells.add(cell);basin.cellCount++;
            basin.minY=Math.min(basin.minY,cell.getY());basin.maxY=Math.max(basin.maxY,cell.getY());
            basin.cellsByLevel.computeIfAbsent(cell.getY(),y->new ArrayList<>()).add(cell);data.basinByCell.put(cell,id);
        }
        for(DraftBasin basin:data.basins)if(basin.cells.isEmpty())throw new IllegalArgumentException("Empty fuel basin");
        return new FuelTankGeometry(data,cells,perBlock);
    }

    public static final class Basin {
        public final int id,minY,maxY;
        public final boolean ridge;
        public final List<BlockPos> cells;
        private final NavigableMap<Integer,List<BlockPos>> cellsByLevel;
        private final int[] ys;
        private final long[] levelCaps,prefix;
        private final int originY;
        private Basin(DraftBasin draft,int originY,int perBlock) {
            id=draft.id; minY=draft.minY; maxY=draft.maxY; ridge=draft.ridge; this.originY=originY;
            cells=List.copyOf(draft.cells); TreeMap<Integer,List<BlockPos>> levels=new TreeMap<>();
            draft.cellsByLevel.forEach((y,ps)->levels.put(y,List.copyOf(ps)));
            cellsByLevel=Collections.unmodifiableNavigableMap(levels);
            ys=new int[levels.size()]; levelCaps=new long[ys.length]; prefix=new long[ys.length+1];
            int i=0; for(var entry:levels.entrySet()) {
                ys[i]=entry.getKey()-originY; levelCaps[i]=(long)entry.getValue().size()*perBlock;
                prefix[i+1]=prefix[i]+levelCaps[i]; i++;
            }
        }
        public NavigableMap<Integer,List<BlockPos>> cellsByLevel() {return cellsByLevel;}
        public long capacity() {return prefix[ys.length];}
        public double floor() {return minY-originY;}
        public double ceiling() {return (double)maxY-originY+1;}
        public long volumeAt(double height) {
            int lo=0,hi=ys.length;
            while(lo<hi) {int mid=(lo+hi)>>>1;if(ys[mid]+1<=height)lo=mid+1;else hi=mid;}
            long volume=prefix[lo];
            if(lo<ys.length && height>ys[lo]) volume+=Math.min(levelCaps[lo],(long)Math.floor(levelCaps[lo]*(height-ys[lo])));
            return volume;
        }
        public double heightFor(long volume) {
            if(volume<=0)return floor(); if(volume>=capacity())return ceiling();
            int lo=0,hi=ys.length-1;
            while(lo<hi) {int mid=(lo+hi)>>>1;if(prefix[mid+1]>=volume)hi=mid;else lo=mid+1;}
            return ys[lo]+(double)(volume-prefix[lo])/levelCaps[lo];
        }
    }
    private static final class Draft {
        final List<DraftBasin> basins=new ArrayList<>();
        final Map<BlockPos,Integer> basinByCell=new HashMap<>();
    }
    private static final class DraftBasin {
        int id; boolean ridge; int minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE;
        int cellCount; final List<BlockPos> cells=new ArrayList<>();
        final TreeMap<Integer,List<BlockPos>> cellsByLevel=new TreeMap<>();
    }
	public static FuelTankGeometry build(Set<BlockPos> cells, int perBlock) {
		Draft data = new Draft();
		if (cells.isEmpty() || perBlock <= 0) throw new IllegalArgumentException("Empty geometry or nonpositive capacity");
        // A solid box has exactly one drainage basin. Avoid the watershed's maps,
        // union-find and repeated neighbor walks for the common flat/column/box shapes.
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(BlockPos p:cells) {
            minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());minZ=Math.min(minZ,p.getZ());
            maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());
        }
        long width=(long)maxX-minX+1,height=(long)maxY-minY+1,depth=(long)maxZ-minZ+1;
        if(width<=cells.size() && height<=cells.size() && depth<=cells.size()
                && width*height<=cells.size() && width*height*depth==cells.size()) {
            DraftBasin basin=new DraftBasin();basin.id=0;basin.minY=minY;basin.maxY=maxY;basin.cellCount=cells.size();
            for(BlockPos p:cells.stream().sorted(FuelTankGeometry::comparePos).toList()) {
                basin.cells.add(p);basin.cellsByLevel.computeIfAbsent(p.getY(),y->new ArrayList<>()).add(p);
                data.basinByCell.put(p,0);
            }
            data.basins.add(basin);return new FuelTankGeometry(data,cells,perBlock);
        }

		int n = cells.size();
		// HashSet iteration depends on the collected positions, not their geometric order.
		// DraftBasin ids and the fill/drain tie-breaks derive from this array's order, so
		// sorting makes the whole decomposition a pure function of the shape — the fill
		// and drain order is then decided by shape + injection/extraction position only.
		BlockPos[] arr = cells.toArray(new BlockPos[0]);
		Arrays.sort(arr, (a, b) -> comparePos(a, b));
		Map<BlockPos, Integer> index = new HashMap<>();
		for (int i = 0; i < n; i++)
			index.put(arr[i], i);

		// union-find over cells
		int[] find = new int[n];
		for (int i = 0; i < n; i++)
			find[i] = i;
		java.util.function.IntUnaryOperator findOp = i -> {
			int root = i;
			while (find[root] != root)
				root = find[root];
			while (find[i] != i) {
				int next = find[i];
				find[i] = root;
				i = next;
			}
			return root;
		};
		java.util.function.BiConsumer<Integer, Integer> union = (a, b) -> {
			int ra = findOp.applyAsInt(a), rb = findOp.applyAsInt(b);
			if (ra != rb)
				find[rb] = ra;
		};

		// 1. local minima
		Set<Integer> minima = new HashSet<>();
		for (int i = 0; i < n; i++) {
			BlockPos p = arr[i];
			boolean isMin = true;
			for (Direction d : DIRECTIONS) {
				Integer j = index.get(p.relative(d));
				if (j != null && arr[j].getY() < p.getY()) {
					isMin = false;
					break;
				}
			}
			if (isMin)
				minima.add(i);
		}
		// union equal-height adjacent minima into plateaus
		for (int i : minima) {
			BlockPos p = arr[i];
			for (Direction d : DIRECTIONS) {
				Integer j = index.get(p.relative(d));
				if (j != null && minima.contains(j) && arr[j].getY() == p.getY())
					union.accept(i, j);
			}
		}
		Map<Integer, Integer> sinkRootToDraftBasin = new HashMap<>();
		for (int i : minima)
			sinkRootToDraftBasin.computeIfAbsent(findOp.applyAsInt(i), k -> sinkRootToDraftBasin.size());

		// 2. assign every cell to the sink it drains to
		int[] basinOfCell = new int[n];
		boolean[] resolved = new boolean[n];
		for (int i = 0; i < n; i++) {
			List<Integer> path = new ArrayList<>();
			int cur = i;
			while (true) {
				if (resolved[cur]) {
					int b = basinOfCell[cur];
					for (int p : path)
						basinOfCell[p] = b;
					for (int p : path)
						resolved[p] = true;
					break;
				}
				if (minima.contains(cur)) {
					int b = sinkRootToDraftBasin.get(findOp.applyAsInt(cur));
					basinOfCell[cur] = b;
					resolved[cur] = true;
					for (int p : path)
						basinOfCell[p] = b;
					for (int p : path)
						resolved[p] = true;
					break;
				}
				path.add(cur);
				int best = -1;
				for (Direction d : DIRECTIONS) {
					Integer j = index.get(arr[cur].relative(d));
					if (j == null || arr[j].getY() >= arr[cur].getY())
						continue;
					if (best == -1 || arr[j].getY() < arr[best].getY()
							|| (arr[j].getY() == arr[best].getY() && comparePos(arr[j], arr[best]) < 0))
						best = j;
				}
				if (best == -1) {
					// unreachable; treat as its own sink
					int b = sinkRootToDraftBasin.size();
					sinkRootToDraftBasin.put(cur, b);
					minima.add(cur);
					basinOfCell[cur] = b;
					resolved[cur] = true;
					for (int p : path)
						basinOfCell[p] = b;
					for (int p : path)
						resolved[p] = true;
					break;
				}
				cur = best;
			}
		}

		// 3. build DraftBasin objects
		Map<Integer, DraftBasin> byId = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			int b = basinOfCell[i];
			DraftBasin basin = byId.computeIfAbsent(b, k -> new DraftBasin());
			basin.cells.add(arr[i]);
			basin.cellsByLevel.computeIfAbsent(arr[i].getY(), k -> new ArrayList<>()).add(arr[i]);
			basin.minY = Math.min(basin.minY, arr[i].getY());
			basin.maxY = Math.max(basin.maxY, arr[i].getY());
			basin.cellCount++;
		}
		int basinCount = byId.size();
		DraftBasin[] basinArr = new DraftBasin[basinCount];
		for (Map.Entry<Integer, DraftBasin> entry : byId.entrySet()) {
			int bi = entry.getKey();
			DraftBasin basin = entry.getValue();
			basin.id = bi;
			basinArr[bi] = basin;
			// ridge = every cell is a local minimum (no drainage from above)
			boolean ridge = true;
			for (BlockPos p : basin.cells) {
				if (!minima.contains(index.get(p))) {
					ridge = false;
					break;
				}
			}
			basin.ridge = ridge;
		}
		data.basins.addAll(Arrays.asList(basinArr));

		// 3.5 shelf merge — a local-minimum basin is a true saddle only when it touches
		// >= 2 basins AND sits at or above every neighbour's highest cell (a "peak"
		// separating them: inverted-U top bar, table top, ring's top bar). Otherwise it is
		// a dead-end or mid-level "shelf" (a tree branch, a T arm, a mid-height plate that
		// neighbours extend above) and merges into the adjacent basin with the lowest
		// base, so same-level connected cells share one flat surface.
		boolean[] allMin = new boolean[basinCount];
		int[] basinMinY = new int[basinCount];
		int[] basinMaxY = new int[basinCount];
		for (int i = 0; i < basinCount; i++) {
			allMin[i] = data.basins.get(i).ridge;
			basinMinY[i] = Integer.MAX_VALUE;
			basinMaxY[i] = Integer.MIN_VALUE;
		}
		for (int i = 0; i < n; i++) {
			int b = basinOfCell[i];
			basinMinY[b] = Math.min(basinMinY[b], arr[i].getY());
			basinMaxY[b] = Math.max(basinMaxY[b], arr[i].getY());
		}
		@SuppressWarnings("unchecked")
		Set<Integer>[] nbors = (Set<Integer>[]) new Set<?>[basinCount];
		for (int i = 0; i < basinCount; i++)
			nbors[i] = new HashSet<>();
		for (int i = 0; i < n; i++) {
			int bSrc = basinOfCell[i];
			for (Direction d : DIRECTIONS) {
				Integer j = index.get(arr[i].relative(d));
				if (j == null)
					continue;
				int bDst = basinOfCell[j];
				if (bDst != bSrc) {
					nbors[bSrc].add(bDst);
					nbors[bDst].add(bSrc);
				}
			}
		}
		boolean[] isSaddle = new boolean[basinCount];
		for (int b = 0; b < basinCount; b++)
			isSaddle[b] = allMin[b] && nbors[b].size() >= 2;
		boolean merged;
		do {
			merged = false;
			for (int b = 0; b < basinCount; b++) {
				if (!allMin[b] || isSaddle[b] || nbors[b].isEmpty())
					continue;
				int target = -1;
				for (int nb : nbors[b])
					if (target == -1 || basinMinY[nb] < basinMinY[target]
							|| (basinMinY[nb] == basinMinY[target] && nb < target))
						target = nb;
				for (int i = 0; i < n; i++)
					if (basinOfCell[i] == b)
						basinOfCell[i] = target;
				basinMinY[target] = Math.min(basinMinY[target], basinMinY[b]);
				basinMaxY[target] = Math.max(basinMaxY[target], basinMaxY[b]);
				nbors[target].remove(b);
				nbors[b].clear();
				allMin[b] = false;
				merged = true;
			}
		} while (merged);

		// 4. saddle truncation — every cell at or above an adjacent saddle's height merges
		// into the *highest* such saddle (the top bars claim the leg tops before the lower
		// plate claims the chimneys; independent of iteration order). This cuts columns
		// that pass through a saddle level — a leg below an inverted-U bar keeps only its
		// part beneath it, the cells above (chimneys / the vessel) join the saddle.
		int guard = 0;
		boolean moved;
		do {
			moved = false;
			for (int i = 0; i < n; i++) {
				int src = basinOfCell[i];
				if (isSaddle[src])
					continue; // ridge cells never leave the ridge
				int bestDst = -1;
				int bestH = Integer.MIN_VALUE;
				for (Direction d : DIRECTIONS) {
					Integer j = index.get(arr[i].relative(d));
					if (j == null)
						continue;
					int dst = basinOfCell[j];
					if (dst == src || !isSaddle[dst])
						continue; // only move into an adjacent ridge
					int h = basinMaxY[dst];
					if (arr[i].getY() >= h && h > bestH) {
						bestH = h;
						bestDst = dst;
					}
				}
				if (bestDst != -1) {
					basinOfCell[i] = bestDst;
					moved = true;
				}
			}
		} while (moved && guard++ < 1024);

		// 4.5 merge face-adjacent ridge basins at the same level — the watershed can
		// split one saddle plateau around cells that are not local minima (the two top
		// bars of a 3-leg tank meet through a leg top); the truncation reconnects them,
		// so the pieces merge back into a single ridge.
		for (int i = 0; i < basinCount; i++)
			nbors[i].clear();
		for (int i = 0; i < n; i++) {
			int bSrc = basinOfCell[i];
			for (Direction d : DIRECTIONS) {
				Integer j = index.get(arr[i].relative(d));
				if (j == null)
					continue;
				int bDst = basinOfCell[j];
				if (bDst != bSrc) {
					nbors[bSrc].add(bDst);
					nbors[bDst].add(bSrc);
				}
			}
		}
		boolean ridgeMerged;
		do {
			ridgeMerged = false;
			for (int a = 0; a < basinCount; a++) {
				if (!isSaddle[a])
					continue;
				for (int b : new ArrayList<>(nbors[a])) {
					if (!isSaddle[b] || b <= a || basinMaxY[a] != basinMaxY[b])
						continue;
					for (int i = 0; i < n; i++)
						if (basinOfCell[i] == b)
							basinOfCell[i] = a;
					for (int nb : new ArrayList<>(nbors[b])) {
						if (nb == a || nb == b) continue;
						nbors[nb].remove(b);
						nbors[nb].add(a);
						nbors[a].add(nb);
					}
					nbors[a].remove(b);
					nbors[a].remove(a);
					nbors[b].clear();
					isSaddle[b] = false;
					ridgeMerged = true;
					break;
				}
			}
		} while (ridgeMerged);

		// 5. re-decompose valleys by (original basin id, connected component): a saddle
		// can cut a column apart (an asymmetric inverted-U tall leg becomes the part below
		// the bar and the chimney above it), so each disconnected piece becomes its own
		// basin; adjacent but distinct basins never merge. Saddles keep a single id each.
		int saddleCount = 0;
		Map<Integer, Integer> saddleNewId = new HashMap<>();
		for (int b = 0; b < basinCount; b++)
			if (isSaddle[b])
				saddleNewId.put(b, saddleCount++);
		int[] compId = new int[n];
		Arrays.fill(compId, -1);
		int nextId = saddleCount;
		for (int i = 0; i < n; i++) {
			if (isSaddle[basinOfCell[i]] || compId[i] != -1)
				continue;
			int myId = basinOfCell[i];
			Deque<Integer> queue = new ArrayDeque<>();
			queue.add(i);
			compId[i] = nextId;
			int cid = nextId++;
			while (!queue.isEmpty()) {
				int cur = queue.poll();
				for (Direction d : DIRECTIONS) {
					Integer j = index.get(arr[cur].relative(d));
					if (j == null)
						continue;
					int jb = basinOfCell[j];
					if (isSaddle[jb] || compId[j] != -1 || jb != myId)
						continue;
					compId[j] = cid;
					queue.add(j);
				}
			}
		}
		// final assignment
		for (int i = 0; i < n; i++)
			basinOfCell[i] = isSaddle[basinOfCell[i]] ? saddleNewId.get(basinOfCell[i]) : compId[i];
		int finalCount = nextId;

		// rebuild basins
		final int numSaddles = saddleCount;
		Map<Integer, DraftBasin> newById = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			int b = basinOfCell[i];
			DraftBasin nb = newById.computeIfAbsent(b, k -> {
				DraftBasin b2 = new DraftBasin();
				b2.id = k;
				b2.ridge = k < numSaddles;
				return b2;
			});
			nb.cells.add(arr[i]);
			nb.cellsByLevel.computeIfAbsent(arr[i].getY(), k2 -> new ArrayList<>()).add(arr[i]);
			nb.minY = Math.min(nb.minY, arr[i].getY());
			nb.maxY = Math.max(nb.maxY, arr[i].getY());
			nb.cellCount++;
		}
		DraftBasin[] basinArr2 = new DraftBasin[finalCount];
		for (Map.Entry<Integer, DraftBasin> e : newById.entrySet())
			basinArr2[e.getKey()] = e.getValue();
		data.basins.clear();
		data.basins.addAll(Arrays.asList(basinArr2));

		// basinByCell
		data.basinByCell.clear();
		for (int i = 0; i < n; i++)
			data.basinByCell.put(arr[i], basinOfCell[i]);


		return new FuelTankGeometry(data, cells, perBlock);
	}

}
