package com.kkc.sheettracker.viewer3d

import com.kkc.sheettracker.data.cvjob.CvWingGeometry
import com.kkc.sheettracker.data.cvjob.CvWingNode
import com.kkc.sheettracker.data.cvjob.CvWingNodeMaterial
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.visibleNodes
import kotlin.math.cos
import kotlin.math.sin

data class WingBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double
) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val depth get() = maxZ - minZ

    fun overlap(other: WingBounds): WingBounds? {
        val minX = maxOf(minX, other.minX)
        val minY = maxOf(minY, other.minY)
        val minZ = maxOf(minZ, other.minZ)
        val maxX = minOf(maxX, other.maxX)
        val maxY = minOf(maxY, other.maxY)
        val maxZ = minOf(maxZ, other.maxZ)
        return if (maxX - minX > EPSILON && maxY - minY > EPSILON && maxZ - minZ > EPSILON) {
            WingBounds(minX, minY, minZ, maxX, maxY, maxZ)
        } else null
    }

    companion object { private const val EPSILON = 0.000_001 }
}

data class WingScenePart(
    val path: String,
    val code: String,
    val doorId: String?,
    val material: CvWingNodeMaterial?,
    val transform: WingMatrix,
    val bounds: WingBounds,
    /** Paneled DOR meshes retain their verified component source rather than borrowing a board material. */
    val usesDoorComponents: Boolean
)

data class WingCapEngagement(val capPath: String, val boardPath: String, val depth: Double)
data class WingScenePayload(
    val cabinet: String,
    val parts: List<WingScenePart>,
    val bounds: WingBounds,
    val capEngagements: List<WingCapEngagement>
)

/** Builds renderable parts after all ancestor transforms, without mutating source geometry. */
fun buildWingScenePayload(
    wing: CvWingGeometry,
    doorsById: Map<String, DoorPreviewData>
): WingScenePayload {
    val transforms = mutableMapOf<String, WingMatrix>()
    wing.nodes.forEach { node ->
        val parent = node.parent?.let { transforms[it] }
            ?: if (node.parent == null) WingMatrix.identity else error("missing parent ${node.parent}")
        transforms[node.path] = parent * node.localTransform()
    }
    val parts = wing.visibleNodes().map { node ->
        val transform = requireNotNull(transforms[node.path])
        WingScenePart(
            path = node.path,
            code = node.code,
            doorId = node.doorId,
            material = node.material,
            transform = transform,
            bounds = transform.boundsFor(node),
            usesDoorComponents = node.doorId != null && doorsById.containsKey(node.doorId)
        )
    }
    val allBounds = parts.map { it.bounds }.reduce(::union)
    return WingScenePayload(
        cabinet = wing.cabinet,
        parts = parts,
        bounds = allBounds,
        capEngagements = capEngagements(parts)
    )
}

private fun capEngagements(parts: List<WingScenePart>): List<WingCapEngagement> =
    parts.filter { it.code == "FILLER" }.flatMap { cap ->
        parts.filter { it.code in setOf("LF", "RF", "DOR") }.mapNotNull { board ->
            cap.bounds.overlap(board.bounds)?.let { overlap ->
                WingCapEngagement(cap.path, board.path, overlap.depth)
            }
        }
    }

private fun CvWingNode.localTransform(): WingMatrix =
    WingMatrix.translation(position.x, position.y, position.z) *
        WingMatrix.rotationZ(rotationDegrees.z) *
        WingMatrix.rotationY(rotationDegrees.y) *
        WingMatrix.rotationX(rotationDegrees.x)

private fun WingMatrix.boundsFor(node: CvWingNode): WingBounds {
    val xs = listOf(0.0, node.size.width)
    val ys = listOf(0.0, node.size.height)
    val zs = listOf(0.0, node.size.depth)
    val corners = xs.flatMap { x -> ys.flatMap { y -> zs.map { z -> transform(x, y, z) } } }
    return WingBounds(
        corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z },
        corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }
    )
}

private fun union(left: WingBounds, right: WingBounds) = WingBounds(
    minOf(left.minX, right.minX), minOf(left.minY, right.minY), minOf(left.minZ, right.minZ),
    maxOf(left.maxX, right.maxX), maxOf(left.maxY, right.maxY), maxOf(left.maxZ, right.maxZ)
)

data class WingPoint(val x: Double, val y: Double, val z: Double)

/** Row-major homogeneous matrix. Local rotations apply X, then Y, then Z. */
class WingMatrix private constructor(private val values: DoubleArray) {
    operator fun times(other: WingMatrix): WingMatrix = WingMatrix(DoubleArray(16) { index ->
        val row = index / 4
        val column = index % 4
        (0..3).sumOf { values[row * 4 + it] * other.values[it * 4 + column] }
    })

    fun transform(x: Double, y: Double, z: Double): WingPoint = WingPoint(
        values[0] * x + values[1] * y + values[2] * z + values[3],
        values[4] * x + values[5] * y + values[6] * z + values[7],
        values[8] * x + values[9] * y + values[10] * z + values[11]
    )

    companion object {
        val identity = WingMatrix(doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        ))

        fun translation(x: Double, y: Double, z: Double) = WingMatrix(doubleArrayOf(
            1.0, 0.0, 0.0, x,
            0.0, 1.0, 0.0, y,
            0.0, 0.0, 1.0, z,
            0.0, 0.0, 0.0, 1.0
        ))

        fun rotationX(degrees: Double): WingMatrix {
            val (c, s) = trig(degrees)
            return WingMatrix(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0))
        }

        fun rotationY(degrees: Double): WingMatrix {
            val (c, s) = trig(degrees)
            return WingMatrix(doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0))
        }

        fun rotationZ(degrees: Double): WingMatrix {
            val (c, s) = trig(degrees)
            return WingMatrix(doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        }

        private fun trig(degrees: Double): Pair<Double, Double> {
            val radians = Math.toRadians(degrees)
            return cos(radians) to sin(radians)
        }
    }
}
