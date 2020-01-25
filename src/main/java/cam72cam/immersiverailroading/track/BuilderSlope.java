package cam72cam.immersiverailroading.track;

import cam72cam.immersiverailroading.util.RailInfo;
import cam72cam.mod.math.Vec3i;

public class BuilderSlope extends BuilderStraight {
	public BuilderSlope(RailInfo info, Vec3i pos) {
		super(info, pos);
	}

	@Override
	public CubicCurve getCurve() {
		CubicCurve curve = super.getCurve();
		return new CubicCurve(
				curve.p1,
				curve.ctrl1.add(0, 0.25, 0),
				curve.ctrl2.add(0, 0.75, 0),
				curve.p2.add(0, 1, 0)
		);
	}
}
