package cam72cam.immersiverailroading.util;

import cam72cam.immersiverailroading.items.ItemRail;
import cam72cam.immersiverailroading.library.TrackDirection;
import cam72cam.immersiverailroading.library.TrackItems;
import cam72cam.immersiverailroading.track.BuilderBase;
import cam72cam.immersiverailroading.track.BuilderCrossing;
import cam72cam.immersiverailroading.track.BuilderSlope;
import cam72cam.immersiverailroading.track.BuilderStraight;
import cam72cam.immersiverailroading.track.BuilderTurn;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

public class RailInfo {
	public BlockPos position;
	public World world;
	public EnumFacing facing;
	public TrackItems type;
	public TrackDirection direction;
	public int length;
	public int quarter;
	public int quarters;
	public float horizOff;

	public boolean snowRenderFlagDirty = false;
	
	
	public RailInfo(BlockPos position, World world, EnumFacing facing, TrackItems type, TrackDirection direction, int length, int quarter, int quarters, float horizOff) {
		this.position = position;
		this.world = world;
		this.facing = facing;
		this.type = type;
		this.direction = direction;
		this.length = length;
		this.quarter = quarter;
		this.quarters = quarters;
		this.horizOff = horizOff;
	}
	
	public RailInfo(ItemStack stack, EntityPlayer player, BlockPos pos, float hitX, float hitY, float hitZ) {
		position = pos;
		world = player.getEntityWorld();
		length = ItemRail.getLength(stack);
		quarters = ItemRail.getQuarters(stack);
		
		float yawHead = player.getRotationYawHead() % 360 + 360;
		direction = (yawHead % 90 < 45) ? TrackDirection.RIGHT : TrackDirection.LEFT;
		quarter = MathHelper.floor((yawHead % 90f) /90*4);
		if (direction == TrackDirection.LEFT) {
			quarter = (3-quarter+4) % 4;
		}
		
		facing = player.getHorizontalFacing();
		type = TrackItems.fromMeta(stack.getMetadata());

		
		if (type == TrackItems.STRAIGHT && quarter != 0) {
			hitX = ((int)(hitX * 10)) / 10f;
			hitZ = ((int)(hitZ * 10)) / 10f;
		} else {
			hitX = 0.5f;
			hitZ = 0.5f;
		}
		
		horizOff = 0;
		switch (facing) {
		case WEST:
			horizOff = hitZ;
			break;
		case EAST:
			horizOff = 1-hitZ;
			break;
		case SOUTH:
			horizOff = hitX;
			break;
		case NORTH:
			horizOff = 1-hitX;
			break;
		default:
			break;
		}
		
		if (BlockUtil.canBeReplaced(world, pos.down(), true)) {
			pos = pos.down();
		}
	}
	
	public BuilderBase getBuilder(BlockPos pos) {
		switch (type) {
		case STRAIGHT:
			return new BuilderStraight(this, pos);
		case CROSSING:
			return new BuilderCrossing(this, pos);
		case SLOPE:
			return new BuilderSlope(this, pos);
		case TURN:
			return new BuilderTurn(this, pos);
		default:
			return null;
		}
	}
	
	private BuilderBase builder;
	public BuilderBase getBuilder() {
		if (builder == null) {
			builder = getBuilder(new BlockPos(0,0,0));
		}
		return builder;
	}
}