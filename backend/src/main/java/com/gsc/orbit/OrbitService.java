package com.gsc.orbit;

import com.gsc.catalog.CatalogRepository;
import com.gsc.catalog.GroundStation;
import com.gsc.catalog.Satellite;
import com.gsc.config.GscProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.ode.events.Action;
import org.hipparchus.util.FastMath;
import org.orekit.bodies.CelestialBody;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.ElevationDetector;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orbit mechanics for the tracked satellite: SGP4 propagation of the TLE, antenna look angles
 * from the ground station, pass (AOS/LOS) prediction and eclipse state.
 *
 * <p>This is the single source of truth for visibility; the browser only renders what it is sent.
 */
@Service
public class OrbitService {

    private static final Logger log = LoggerFactory.getLogger(OrbitService.class);
    private static final double PASS_SAMPLE_STEP_SEC = 5.0;

    private final Satellite satellite;
    private final GroundStation station;
    private final TimeScale utc;
    private final Frame itrf;
    private final Frame inertial;
    private final OneAxisEllipsoid earth;
    private final TopocentricFrame topo;
    private final CelestialBody sun;
    private final double minElevationRad;
    private final TLE tle;

    @Autowired
    public OrbitService(GscProperties props, CatalogRepository catalog) {
        this(withFreshTle(props, catalog), catalog.findStation(props.stationId()), props.orekitDataPath());
    }

    public OrbitService(Satellite satellite, GroundStation station, String orekitDataPath) {
        OrekitBootstrap.ensureLoaded(orekitDataPath);
        this.satellite = satellite;
        this.station = station;
        this.utc = TimeScalesFactory.getUTC();
        this.itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        this.inertial = FramesFactory.getEME2000();
        this.earth = new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING, itrf);
        this.topo = new TopocentricFrame(earth, new GeodeticPoint(FastMath.toRadians(station.latDeg()),
                FastMath.toRadians(station.lonDeg()), station.altM()), station.name());
        this.sun = CelestialBodyFactory.getSun();
        this.minElevationRad = FastMath.toRadians(station.minElevationDeg());
        this.tle = new TLE(satellite.tleLine1(), satellite.tleLine2());
        log.info("Tracking {} (TLE epoch {}) from {}", satellite.name(), tle.getDate(), station.name());
    }

    public Satellite satellite() {
        return satellite;
    }

    public GroundStation station() {
        return station;
    }

    public Instant tleEpoch() {
        return toInstant(tle.getDate());
    }

    public SubPoint subPoint(Instant t) {
        AbsoluteDate date = toDate(t);
        Vector3D pos = propagator().getPosition(date, itrf);
        GeodeticPoint gp = earth.transform(pos, itrf, date);
        return new SubPoint(t, FastMath.toDegrees(gp.getLatitude()), FastMath.toDegrees(gp.getLongitude()),
                gp.getAltitude() / 1000.0);
    }

    public LookAngles look(Instant t) {
        return look(propagator(), toDate(t));
    }

    public boolean isVisible(Instant t) {
        return look(t).elevationDeg() >= station.minElevationDeg();
    }

    /** Cylindrical Earth-shadow model: good enough to drive a power budget. */
    public boolean isSunlit(Instant t) {
        AbsoluteDate date = toDate(t);
        Vector3D sat = propagator().getPosition(date, inertial);
        Vector3D sunDir = sun.getPosition(date, inertial).normalize();
        double along = sat.dotProduct(sunDir);
        if (along >= 0) {
            return true;
        }
        double distanceFromShadowAxis = sat.subtract(sunDir.scalarMultiply(along)).getNorm();
        return distanceFromShadowAxis > Constants.WGS84_EARTH_EQUATORIAL_RADIUS;
    }

    public List<SubPoint> groundTrack(Instant from, Instant to, Duration step) {
        List<SubPoint> points = new ArrayList<>();
        for (Instant t = from; !t.isAfter(to); t = t.plus(step)) {
            points.add(subPoint(t));
        }
        return points;
    }

    /** Antenna az/el over a time span: the curve an operator sees on a polar sky plot. */
    public List<LookAngles> skyTrack(Instant from, Instant to, Duration step) {
        TLEPropagator propagator = propagator();
        List<LookAngles> points = new ArrayList<>();
        for (Instant t = from; !t.isAfter(to); t = t.plus(step)) {
            points.add(look(propagator, toDate(t)));
        }
        points.add(look(propagator, toDate(to)));
        return points;
    }

    /**
     * Passes whose LOS is after {@code from}, found with an Orekit elevation event detector
     * (root-finding on elevation - mask). A pass already in progress at {@code from} is returned
     * with its true AOS, so the search starts one maximum pass length earlier.
     */
    public List<Pass> predictPasses(Instant from, Duration horizon, int maxCount) {
        TLEPropagator propagator = propagator();
        AbsoluteDate start = toDate(from.minus(Duration.ofMinutes(20)));
        AbsoluteDate end = toDate(from.plus(horizon));
        List<AbsoluteDate[]> windows = new ArrayList<>();
        AbsoluteDate[] openAos = {look(propagator, start).elevationDeg() >= station.minElevationDeg() ? start : null};

        ElevationDetector detector = new ElevationDetector(topo)
                .withConstantElevation(minElevationRad)
                .withMaxCheck(30.0)
                .withThreshold(1.0e-3)
                .withHandler((state, d, increasing) -> {
                    if (increasing) {
                        openAos[0] = state.getDate();
                    } else if (openAos[0] != null) {
                        windows.add(new AbsoluteDate[] {openAos[0], state.getDate()});
                        openAos[0] = null;
                    }
                    return Action.CONTINUE;
                });
        propagator.addEventDetector(detector);
        propagator.propagate(start, end);

        AbsoluteDate fromDate = toDate(from);
        return windows.stream()
                .filter(w -> w[1].isAfter(fromDate))
                .limit(maxCount)
                .map(w -> describePass(w[0], w[1]))
                .toList();
    }

    private Pass describePass(AbsoluteDate aos, AbsoluteDate los) {
        TLEPropagator propagator = propagator();
        AbsoluteDate tca = aos;
        double maxEl = Double.NEGATIVE_INFINITY;
        for (AbsoluteDate t = aos; t.compareTo(los) <= 0; t = t.shiftedBy(PASS_SAMPLE_STEP_SEC)) {
            double el = look(propagator, t).elevationDeg();
            if (el > maxEl) {
                maxEl = el;
                tca = t;
            }
        }
        return new Pass(toInstant(aos), toInstant(los), toInstant(tca), maxEl,
                look(propagator, aos).azimuthDeg(), look(propagator, los).azimuthDeg());
    }

    private LookAngles look(TLEPropagator propagator, AbsoluteDate date) {
        Vector3D pos = propagator.getPosition(date, itrf);
        return new LookAngles(
                FastMath.toDegrees(topo.getAzimuth(pos, itrf, date)),
                FastMath.toDegrees(topo.getElevation(pos, itrf, date)),
                topo.getRange(pos, itrf, date) / 1000.0);
    }

    /** Orekit propagators are not thread-safe; SGP4 initialisation is cheap, so build one per call. */
    private TLEPropagator propagator() {
        return TLEPropagator.selectExtrapolator(tle);
    }

    private AbsoluteDate toDate(Instant t) {
        return new AbsoluteDate(Date.from(t), utc);
    }

    private Instant toInstant(AbsoluteDate date) {
        return date.toDate(utc).toInstant();
    }

    private static Satellite withFreshTle(GscProperties props, CatalogRepository catalog) {
        Satellite sat = catalog.findSatellite(props.satelliteId());
        if (!props.tle().refreshOnStartup()) {
            return sat;
        }
        try {
            String[] lines = CelestrakClient.fetchTle(props.tle().sourceUrl(), sat.noradId());
            catalog.updateTle(sat.id(), lines[0], lines[1]);
            log.info("Refreshed TLE for {} from Celestrak", sat.name());
            return new Satellite(sat.id(), sat.name(), sat.noradId(), lines[0], lines[1]);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("TLE refresh failed ({}); using stored TLE for {}", e.getMessage(), sat.name());
            return sat;
        }
    }
}
