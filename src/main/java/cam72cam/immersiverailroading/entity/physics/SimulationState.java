package cam72cam.immersiverailroading.entity.physics;

import cam72cam.immersiverailroading.Config;
import cam72cam.immersiverailroading.entity.EntityCoupleableRollingStock;
import cam72cam.immersiverailroading.entity.Locomotive;
import cam72cam.immersiverailroading.entity.physics.chrono.ServerChronoState;
import cam72cam.immersiverailroading.library.Gauge;
import cam72cam.immersiverailroading.physics.MovementTrack;
import cam72cam.immersiverailroading.thirdparty.trackapi.ITrack;
import cam72cam.immersiverailroading.util.BlockUtil;
import cam72cam.immersiverailroading.util.VecUtil;
import cam72cam.mod.entity.boundingbox.IBoundingBox;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.util.DegreeFuncs;
import cam72cam.mod.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static cam72cam.immersiverailroading.entity.EntityMoveableRollingStock.DAMAGE_SOURCE_HIT;

public class SimulationState {
    public int tickID;

    public Vec3d position;
    public Vec3d velocity;
    public float yaw;
    public float pitch;
    public IBoundingBox bounds;

    // Render purposes
    public float yawFront;
    public float yawRear;

    public Vec3d couplerPositionFront;
    public Vec3d couplerPositionRear;
    public UUID interactingFront;
    public UUID interactingRear;
    public List<Vec3i> blocksToBreak = new ArrayList<>();
    public float totalBlockResistance;
    public boolean overcameBlockResistance;

    public Configuration config;

    public static class Configuration {
        public UUID id;
        public Gauge gauge;
        public World world;

        public double width;
        public double length;
        public double height;
        public Function<SimulationState, IBoundingBox> bounds;

        public float offsetFront;
        public float offsetRear;

        public boolean couplerEngagedFront;
        public boolean couplerEngagedRear;
        public double couplerDistanceFront;
        public double couplerDistanceRear;

        public double massKg;
        // TODO these should probably be dynamic
        public double brakeAdhesionNewtons;
        public double tractiveEffortNewtons;

        public Configuration(EntityCoupleableRollingStock stock) {
            id = stock.getUUID();
            gauge = stock.gauge;
            world = stock.getWorld();

            width = stock.getDefinition().getWidth(gauge);
            length = stock.getDefinition().getLength(gauge);
            height = stock.getDefinition().getHeight(gauge);
            bounds = s -> stock.getDefinition().getBounds(s.yaw, gauge)
                    .offset(s.position)
                    .contract(new Vec3d(0, 0.5 * this.gauge.scale(), 0))
                    .offset(new Vec3d(0, 0.5 * this.gauge.scale(), 0));

            offsetFront = stock.getDefinition().getBogeyFront(gauge);
            offsetRear = stock.getDefinition().getBogeyRear(gauge);

            couplerEngagedFront = stock.isCouplerEngaged(EntityCoupleableRollingStock.CouplerType.FRONT);
            couplerEngagedRear = stock.isCouplerEngaged(EntityCoupleableRollingStock.CouplerType.BACK);
            // TODO This is intentional
            couplerDistanceFront = stock.getDefinition().getCouplerPosition(EntityCoupleableRollingStock.CouplerType.BACK, gauge);
            couplerDistanceRear = -stock.getDefinition().getCouplerPosition(EntityCoupleableRollingStock.CouplerType.FRONT, gauge);

            this.massKg = stock.getWeight();

            if (stock instanceof Locomotive) {
                tractiveEffortNewtons = ((Locomotive) stock).getTractiveEffortNewtons(stock.getCurrentSpeed());
            } else {
                tractiveEffortNewtons = 0;
            }

            double totalAdhesionNewtons = stock.getWeight() * 0.25 * 0.25 * 4.44822f;
            brakeAdhesionNewtons = totalAdhesionNewtons * stock.getTotalBrake();
        }
    }

    public SimulationState(EntityCoupleableRollingStock stock) {
        tickID = ServerChronoState.getState(stock.getWorld()).getServerTickID();
        position = stock.getPosition();
        velocity = stock.getVelocity();
        yaw = stock.getRotationYaw();
        pitch = stock.getRotationPitch();

        interactingFront = stock.getCoupledUUID(EntityCoupleableRollingStock.CouplerType.FRONT);
        interactingRear = stock.getCoupledUUID(EntityCoupleableRollingStock.CouplerType.BACK);

        config = new Configuration(stock);

        bounds = config.bounds.apply(this);

        yawFront = stock.getFrontYaw();
        yawRear = stock.getRearYaw();

        calculateCouplerPositions();
    }

    private SimulationState(SimulationState prev) {
        this.tickID = prev.tickID + 1;
        this.position = prev.position;
        this.velocity = prev.velocity;
        this.yaw = prev.yaw;
        this.pitch = prev.pitch;

        this.interactingFront = prev.interactingFront;
        this.interactingRear = prev.interactingRear;

        this.config = prev.config;

        this.bounds = config.bounds.apply(this);

        this.yawFront = prev.yawFront;
        this.yawRear = prev.yawRear;
        //calculateCouplerPositions();
        couplerPositionFront = prev.couplerPositionFront;
        couplerPositionRear = prev.couplerPositionRear;
    }

    public void calculateCouplerPositions() {
        Vec3d couplerVecFront = VecUtil.fromWrongYaw(config.couplerDistanceFront, yaw);
        Vec3d couplerVecRear = VecUtil.fromWrongYaw(config.couplerDistanceRear, yaw);

        ITrack track = MovementTrack.findTrack(config.world, position, yaw, config.gauge.value());
        if (track != null) {
            couplerPositionFront = track.getNextPosition(position, couplerVecFront);
            couplerPositionRear = track.getNextPosition(position, couplerVecRear);
        } else {
            couplerPositionFront = position.add(couplerVecFront);
            couplerPositionRear = position.add(couplerVecRear);
        }
    }

    public SimulationState next() {
        return new SimulationState(this);
    }

    public void update(EntityCoupleableRollingStock stock) {
        config = new Configuration(stock);
    }

    public void collideWithBlocks(List<Vec3i> blocksAlreadyBroken) {
        List<Vec3i> potential = config.world.blocksInBounds(this.bounds);
        potential.removeAll(blocksAlreadyBroken);

        for (Vec3i bp : potential) {
            if (!BlockUtil.isIRRail(config.world, bp)) {
                if (Config.ConfigDamage.TrainsBreakBlocks && config.world.canEntityCollideWith(bp, DAMAGE_SOURCE_HIT)) {
                    if (!BlockUtil.isIRRail(config.world, bp.up())) {
                        totalBlockResistance += config.world.getBlockHardness(bp);
                        blocksToBreak.add(bp);
                    }
                }
            }/* TODO move somewhere else {
                TileRailBase te = config.world.getBlockEntity(bp, TileRailBase.class);
                if (te != null) {
                    te.cleanSnow();
                }
                should probably be an overhead check in TRB?
            }*/
        }
    }

    public void addBlocksBroken(List<Vec3i> blocksAlreadyBroken) {
        if (overcameBlockResistance) {
            blocksAlreadyBroken.addAll(blocksToBreak);
        }
    }

    public void moveAlongTrack(Vec3d vecDist) {
        // TODO turn table stuff

        if (vecDist.lengthSquared() == 0) {
            return;
        }

        double distance = vecDist.length();
        if (DegreeFuncs.delta(VecUtil.toWrongYaw(vecDist), yaw) > 90) {
            distance = -distance;
        }

        Vec3d positionFront = VecUtil.fromWrongYawPitch(config.offsetFront, yaw, pitch).add(position);
        Vec3d positionRear = VecUtil.fromWrongYawPitch(config.offsetRear, yaw, pitch).add(position);

        // Find tracks
        ITrack trackFront = MovementTrack.findTrack(config.world, positionFront, yaw, config.gauge.value());
        ITrack trackRear = MovementTrack.findTrack(config.world, positionRear, yaw, config.gauge.value());
        if (trackFront == null || trackRear == null) {
            System.out.println("OFF");
            return;
        }

        // Fix bogeys pointing in opposite directions
        if (DegreeFuncs.delta(yawFront, yaw) > 90) {
            yawFront = yaw;
        }
        if (DegreeFuncs.delta(yawRear, yaw) > 90) {
            yawRear = yaw;
        }

        boolean isReversed = distance < 0;
        if (isReversed) {
            distance = -distance;
            yaw += 180;
            yawFront += 180;
            yawRear += 180;
        }

        Vec3d nextFront = trackFront.getNextPosition(positionFront, VecUtil.fromWrongYaw(distance, yawFront));
        Vec3d nextRear = trackFront.getNextPosition(positionRear, VecUtil.fromWrongYaw(distance, yawRear));

        if (nextFront.equals(positionFront) || nextRear.equals(positionRear)) {
            // Stuck
            System.out.println("STUCK");
            return;
        }

        yawFront = VecUtil.toWrongYaw(nextFront.subtract(positionFront));
        yawRear = VecUtil.toWrongYaw(nextRear.subtract(positionRear));

        Vec3d currCenter = VecUtil.between(positionFront, positionRear);
        Vec3d nextCenter = VecUtil.between(nextFront, nextRear);
        Vec3d deltaCenter = nextCenter.subtract(currCenter);

        Vec3d bogeyDelta = nextFront.subtract(nextRear);
        yaw = VecUtil.toWrongYaw(bogeyDelta);
        pitch = (float) Math.toDegrees(Math.atan2(bogeyDelta.y, nextRear.distanceTo(nextFront)));
        position = position.add(deltaCenter.normalize().scale(distance)); // Rescale fixes issues with curves losing precision

        if (isReversed) {
            yawFront += 180;
            yawRear += 180;
        }
    }

    public double forcesNewtons() {
        double gradeForceNewtons = config.massKg * -9.8 * Math.sin(Math.toRadians(pitch));
        return config.tractiveEffortNewtons + gradeForceNewtons;
    }

    public double frictionNewtons() {
        // https://evilgeniustech.com/idiotsGuideToRailroadPhysics/OtherLocomotiveForces/#rolling-resistance
        double rollingResistanceNewtons = 0.002 * (config.massKg * 9.8);
        return rollingResistanceNewtons + config.brakeAdhesionNewtons;
    }

    public void apply(EntityCoupleableRollingStock stock) {
        // TODO apply motion + coupler state

        if (overcameBlockResistance) {
            for (Vec3i bp : blocksToBreak) {
                stock.getWorld().breakBlock(bp, Config.ConfigDamage.dropSnowBalls || !(stock.getWorld().isSnow(bp)));
            }
        }
    }
}
