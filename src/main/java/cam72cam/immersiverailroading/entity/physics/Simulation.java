package cam72cam.immersiverailroading.entity.physics;

import cam72cam.immersiverailroading.entity.EntityCoupleableRollingStock;
import cam72cam.immersiverailroading.net.MRSSyncPacket;
import cam72cam.immersiverailroading.physics.TickPos;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.world.World;

import java.util.*;
import java.util.stream.Collectors;

public class Simulation {
    protected List<Map<UUID, SimulationState>> stateMaps = new ArrayList<>();
    protected List<Vec3i> blocksAlreadyBroken = new ArrayList<>();

    private Simulation(World world) {
        // 100KM/h ~= 28m/s which means non-loaded stationary stock may be phased through at that speed
        // I'm OK with that for now
        // We might want to chunk-load ahead of the train just to be safe?

        List<EntityCoupleableRollingStock> allStock = world.getEntities(EntityCoupleableRollingStock.class);
        {
            Map<UUID, SimulationState> stateMap = new HashMap<>();
            for (EntityCoupleableRollingStock entity : allStock) {
                stateMap.put(entity.getUUID(), new SimulationState(entity));
            }
            stateMaps.add(stateMap);
        }

        double maxCouplerDist = 20;

        for (int i = 0; i < 30; i++) {
            // Create new states
            Map<UUID, SimulationState> stateMap = new HashMap<>();
            stateMaps.get(i).forEach((uuid, state) -> stateMap.put(uuid, state.next()));
            List<SimulationState> states = new ArrayList<>(stateMap.values());

            // Decouple / fix coupler positions
            for (SimulationState state : states) {
                if (state.interactingFront != null) {
                    SimulationState next = stateMap.get(state.interactingFront);
                    if (next == null) {
                        state.interactingFront = null;
                    } else {
                        Vec3d myCouplerPos = state.couplerPositionFront;
                        Vec3d nextCouplerPos = state.config.id == next.interactingFront ? next.couplerPositionFront : next.couplerPositionRear;
                        if (myCouplerPos.distanceToSquared(nextCouplerPos) > maxCouplerDist * maxCouplerDist) {
                            System.out.println("DECOUPLER");
                            state.interactingFront = null;
                            if (state.config.id == next.interactingFront) {
                                next.interactingFront = null;
                            } else {
                                next.interactingRear = null;
                            }
                        }
                    }
                }

                if (state.interactingRear != null) {
                    SimulationState next = stateMap.get(state.interactingRear);
                    if (next == null) {
                        state.interactingRear = null;
                    } else {
                        Vec3d myCouplerPos = state.couplerPositionRear;
                        Vec3d nextCouplerPos = state.config.id == next.interactingFront ? next.couplerPositionFront : next.couplerPositionRear;
                        if (myCouplerPos.distanceToSquared(nextCouplerPos) > maxCouplerDist * maxCouplerDist) {
                            System.out.println("DECOUPLER");
                            state.interactingRear = null;
                            if (state.config.id == next.interactingFront) {
                                next.interactingFront = null;
                            } else {
                                next.interactingRear = null;
                            }
                        }
                    }
                }
            }


            // check for potential couplings and collisions
            for (int sai = 0; sai < states.size()-1; sai++) {
                SimulationState stateA = states.get(sai);
                if (stateA.interactingFront != null && stateA.interactingRear != null) {
                    // There's stock in front and behind, can't really hit any other stock here
                    continue;
                }
                for (int sbi = sai+1; sbi < states.size(); sbi++) {
                    SimulationState stateB = states.get(sbi);
                    if (stateB.interactingFront != null && stateB.interactingRear != null) {
                        // There's stock in front and behind, can't really hit any other stock here
                        continue;
                    }

                    if (stateA.config.gauge != stateB.config.gauge) {
                        // Same gauge required
                        continue;
                    }

                    double centerDist = stateA.config.length + stateB.config.length;
                    if (stateA.position.distanceToSquared(stateB.position) > centerDist * centerDist) {
                        // Too far to reasonably couple
                        continue;
                    }

                    if (!stateA.bounds.intersects(stateB.bounds)) {
                        // Not close enough to couple
                        continue;
                    }

                    if (stateA.interactingFront == stateB.config.id || stateA.interactingRear == stateB.config.id) {
                        // Already coupled
                        continue;
                    }
                    if (stateB.interactingFront == stateA.config.id || stateB.interactingRear == stateA.config.id) {
                        // Already coupled (double safe check)
                        continue;
                    }

                    // At this point the stock are colliding / overlapping and we need to do something about it

                    /*
                     * 1. |-----a-----| |-----b-----|
                     * 2. |-----a---|=|----b-----|
                     * 3. |---|=a====b|-----|
                     * Keep in mind that we want to make sure that our other coupler might be a better fit
                     */

                    // the coupler to target is whichever one the other's center is closest to
                    boolean targetACouplerFront =
                            stateA.couplerPositionFront.distanceToSquared(stateB.position) <
                            stateA.couplerPositionRear.distanceToSquared(stateB.position);
                    boolean targetBCouplerFront =
                            stateB.couplerPositionFront.distanceToSquared(stateA.position) <
                            stateB.couplerPositionRear.distanceToSquared(stateA.position);

                    // Best coupler is already coupled to something
                    if ((targetACouplerFront ? stateA.interactingFront : stateA.interactingRear) != null) {
                        continue;
                    }
                    if ((targetBCouplerFront ? stateB.interactingFront : stateB.interactingRear) != null) {
                        continue;
                    }

                    // Ok, we are clear to proceed!
                    if (targetACouplerFront) {
                        stateA.interactingFront = stateB.config.id;
                    } else {
                        stateA.interactingRear = stateB.config.id;
                    }
                    if (targetBCouplerFront) {
                        stateB.interactingFront = stateA.config.id;
                    } else {
                        stateB.interactingRear = stateA.config.id;
                    }
                }
            }

            // collide with blocks
            for (SimulationState state : states) {
                state.collideWithBlocks(blocksAlreadyBroken);
                // TODO use hardness to apply resistance
                state.overcameBlockResistance = true;
            }

            // calculate new velocities
            /*
            List<SimulationState> visited = new ArrayList<>();
            for (SimulationState state : states) {
                if (visited.contains(state)) {
                    continue;
                }

                new Consist(visited, stateMap, state);
            }
             */
            Consist.iterate(stateMap);

            // update blocks broken
            for (SimulationState state : states) {
                state.addBlocksBroken(blocksAlreadyBroken);
            }

            stateMaps.add(stateMap);
        }

        // Apply new states
        for (EntityCoupleableRollingStock stock : allStock) {
            stock.states = stateMaps.stream().map(m -> m.get(stock.getUUID())).collect(Collectors.toList());
            stock.positions = stock.states.stream().map(TickPos::new).collect(Collectors.toList());
            new MRSSyncPacket(stock, stock.positions).sendToObserving(stock);
        }
    }

    public static void simulate(World world) {
        new Simulation(world);
    }
}
