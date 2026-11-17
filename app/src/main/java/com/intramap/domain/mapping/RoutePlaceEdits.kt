package com.intramap.domain.mapping

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class RoutePlaceCategory { ROOM, SHOP, OFFICE, ENTRANCE, JUNCTION, STAIRS, ELEVATOR }

/** A named destination attached to observed geometry; multiple places may share a sample. */
@Serializable
data class RecordedRoutePlace(
    val placeId: String,
    val sampleIndex: Int,
    val name: String,
    val category: RoutePlaceCategory
)

/** Immutable local place edits. This does not modify route geometry or publish destinations. */
class RoutePlaceEdits private constructor(
    private val samples: List<WalkingSample>,
    val places: List<RecordedRoutePlace>,
    val frozen: Boolean,
    private val past: List<List<RecordedRoutePlace>>,
    private val future: List<List<RecordedRoutePlace>>
) {
    val canUndo get() = !frozen && past.isNotEmpty()
    val canRedo get() = !frozen && future.isNotEmpty()
    private fun requireEditable() = check(!frozen) { "Submitted or upload-attempted places cannot be edited" }
    private fun commit(next: List<RecordedRoutePlace>): RoutePlaceEdits {
        requireEditable()
        validate(samples,next)
        return if (next == places) this else RoutePlaceEdits(samples,next.toList(),false,(past + listOf(places)).takeLast(20),emptyList())
    }
    fun add(sampleIndex: Int,name: String,category: RoutePlaceCategory,id: String = UUID.randomUUID().toString()): RoutePlaceEdits {
        requireEditable()
        require(places.none { it.placeId == id }) { "Duplicate place ID" }
        return commit(places + RecordedRoutePlace(id,sampleIndex,name.trim(),category))
    }
    fun rename(id: String,name: String,category: RoutePlaceCategory): RoutePlaceEdits {
        requireEditable()
        require(places.any { it.placeId == id }) { "Unknown place" }
        return commit(places.map { if (it.placeId == id) it.copy(name = name.trim(),category = category) else it })
    }
    fun moveToSample(id: String,sampleIndex: Int): RoutePlaceEdits {
        requireEditable()
        require(places.any { it.placeId == id }) { "Unknown place" }
        return commit(places.map { if (it.placeId == id) it.copy(sampleIndex = sampleIndex) else it })
    }
    fun remove(id: String): RoutePlaceEdits {
        requireEditable()
        require(places.any { it.placeId == id }) { "Unknown place" }
        return commit(places.filterNot { it.placeId == id })
    }
    fun undo(): RoutePlaceEdits {
        requireEditable()
        return if (!canUndo) this else RoutePlaceEdits(samples,past.last(),false,past.dropLast(1),(future + listOf(places)).takeLast(20))
    }
    fun redo(): RoutePlaceEdits {
        requireEditable()
        return if (!canRedo) this else RoutePlaceEdits(samples,future.last(),false,(past + listOf(places)).takeLast(20),future.dropLast(1))
    }
    fun freeze(): RoutePlaceEdits = RoutePlaceEdits(samples,places,true,emptyList(),emptyList())
    fun withSamples(updated: List<WalkingSample>): RoutePlaceEdits {
        requireEditable()
        require(updated.size >= samples.size && updated.take(samples.size) == samples) { "Existing route geometry changed" }
        WalkingTrace.restore(updated)
        return RoutePlaceEdits(updated.toList(),places,false,past,future)
    }
    companion object {
        private fun validate(samples: List<WalkingSample>,places: List<RecordedRoutePlace>) {
            require(places.size <= 1000) { "A recording supports up to 1000 places" }
            require(places.map { it.placeId }.distinct().size == places.size) { "Duplicate place IDs" }
            places.forEach {
                require(UUID.fromString(it.placeId).toString() == it.placeId) { "Invalid place ID" }
                require(it.sampleIndex in samples.indices) { "Select an observed route position" }
                require(it.name == it.name.trim() && it.name.length in 1..120 && it.name.none { c -> c.isISOControl() }) { "Enter a place name of 1 to 120 printable characters" }
            }
        }
        fun restore(samples: List<WalkingSample>,places: List<RecordedRoutePlace> = emptyList(),frozen: Boolean = false): RoutePlaceEdits {
            WalkingTrace.restore(samples)
            validate(samples,places)
            return RoutePlaceEdits(samples.toList(),places.toList(),frozen,emptyList(),emptyList())
        }
    }
}
