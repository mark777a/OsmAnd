package net.osmand.shared.gpx

import net.osmand.shared.gpx.primitives.WptPt
import net.osmand.shared.util.KMapUtils
import net.osmand.shared.util.LoggerFactory

class GpxElevationMerger(readOnlySourceGpxFile: GpxFile, mutableTargetGpxFile: GpxFile) {
    private var nTargetPoints = 0
    private var nMutatedPoints = 0
    private var nInterpolatedPoints = 0
    private val LAT_LON_PRECISION = 0.0001 // ~11m

    private val src: GpxFile = readOnlySourceGpxFile
    private val target: GpxFile = mutableTargetGpxFile
    private var targetMutablePoints: List<WptPt> = mutableListOf()
    private val elevationPoints: MutableMap<Long, Double> = HashMap()

    private val log = LoggerFactory.getLogger("GpxElevationMerger")

    fun merge(): Boolean {
        nTargetPoints = 0
        nMutatedPoints = 0
        nInterpolatedPoints = 0

        calcSourceElevationPoints()
        targetMutablePoints = target.getAllSegmentsPoints()

        mutateTargetPoints()
        ensureTargetSegments()
        interpolateTargetGaps()

        log.info("XXX GpxElevationMerger: targets=$nTargetPoints mutated=$nMutatedPoints interpolated=$nInterpolatedPoints")

        return nMutatedPoints > 0 || nInterpolatedPoints > 0
    }

    private fun calcSourceElevationPoints() {
        elevationPoints.clear()
        for (wpt in src.getAllSegmentsPoints()) {
            if (!wpt.ele.isNaN()) {
                val pointId = calcPointId(wpt)
                if (pointId != 0L) {
                    elevationPoints.put(pointId, wpt.ele)
                }
            }
        }
    }

    private fun mutateTargetPoints() {
        if (elevationPoints.isEmpty()) {
            return
        }
        for (wpt in targetMutablePoints) {
            val pointId = calcPointId(wpt)
            if (pointId == 0L) {
                continue
            }
            val srcElevation = elevationPoints.get(pointId)
            if (srcElevation != null) {
                wpt.ele = srcElevation
                nMutatedPoints++
            }
            nTargetPoints++
        }
    }

    private fun ensureTargetSegments() {
        if (nMutatedPoints + nInterpolatedPoints >= nTargetPoints || elevationPoints.isEmpty()) {
            return
        }
        for (track in target.tracks) {
            if (track.generalTrack) {
                continue
            }
            for (segment in track.segments) {
                if (segment.generalSegment) {
                    continue
                }
                ensureSegmentStartEndPoints(segment.points)
            }
        }
    }

    private fun interpolateTargetGaps() {
        if (nMutatedPoints + nInterpolatedPoints >= nTargetPoints || elevationPoints.isEmpty()) {
            return
        }
        interpolatePointsWithExistingStartEnd(targetMutablePoints)
    }

    private fun ensureSegmentStartEndPoints(points: List<WptPt>): Boolean {
        if (points.isEmpty()) {
            return true
        }
        if (points.first().ele.isNaN()) {
            for (i in 1 until points.size) {
                val ele = points.get(i).ele
                if (!ele.isNaN()) {
                    points.first().ele = ele
                    nInterpolatedPoints++
                    break
                }
            }
        }
        if (points.last().ele.isNaN()) {
            for (i in points.size - 2 downTo 0) {
                val ele = points.get(i).ele
                if (!ele.isNaN()) {
                    points.last().ele = ele
                    nInterpolatedPoints++
                    break
                }
            }
        }
        return !points.first().ele.isNaN() && !points.last().ele.isNaN()
    }

    private fun interpolatePointsWithExistingStartEnd(points: List<WptPt>) {
        if (!ensureSegmentStartEndPoints(points)) {
            return // no start && end => stop
        }
        var i = 1
        while (i < points.size - 1) {
            if (!points.get(i).ele.isNaN()) {
                i++
                continue
            }
            val beforeGapIndex = i - 1
            var afterGapIndex = i + 1
            while (afterGapIndex < points.size && points.get(afterGapIndex).ele.isNaN()) {
                afterGapIndex++
            }
            if (afterGapIndex >= points.size) {
                return // impossible
            }
            val firstEle = points.get(beforeGapIndex).ele
            val lastEle = points.get(afterGapIndex).ele
            if (firstEle.isNaN() || lastEle.isNaN()) {
                return // impossible
            }
            val steps = afterGapIndex - beforeGapIndex
            val step = (lastEle - firstEle) / steps.toDouble()
            for (j in beforeGapIndex + 1 until afterGapIndex) {
                nInterpolatedPoints++
                val thisEle = firstEle + step * (j - beforeGapIndex)
                points.get(j).ele = kotlin.math.round(thisEle * 100.0) / 100.0
            }
            i = afterGapIndex + 1
        }
    }

    private fun calcPointId(wpt: WptPt): Long {
        if (!wpt.hasLocation()) {
            return 0
        }
        val roundLat = kotlin.math.round(wpt.lat / LAT_LON_PRECISION) * LAT_LON_PRECISION
        val roundLon = kotlin.math.round(wpt.lon / LAT_LON_PRECISION) * LAT_LON_PRECISION
        val y31 = KMapUtils.get31TileNumberY(roundLat)
        val x31 = KMapUtils.get31TileNumberX(roundLon)
        return y31.toLong() or (x31.toLong() shl 31)
    }
}
