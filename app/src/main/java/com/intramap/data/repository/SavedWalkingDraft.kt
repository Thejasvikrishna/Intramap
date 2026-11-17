package com.intramap.data.repository

import com.intramap.domain.mapping.RecordedRoutePlace
import com.intramap.domain.mapping.RoutePlaceEdits
import com.intramap.domain.mapping.WalkingSample
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SavedWalkingDraft(
    val account: String, val buildingId: String, val floorKey: String, val recordingId: String,
    val samples: List<WalkingSample>, val revision: Long,
    val uploadAttempted: Boolean = false, val submitted: Boolean = false, val formatVersion: Int = 1,
    val places: List<RecordedRoutePlace> = emptyList()
) {
    fun validateFor(expectedAccount: String,expectedBuilding: String,expectedFloor: String) {
        require(formatVersion in 1..2 && revision >= 0) { "Unsupported recording checkpoint format" }
        require(formatVersion >= 2 || places.isEmpty()) { "Place markers require checkpoint format 2" }
        require(account == expectedAccount && buildingId == expectedBuilding && floorKey == expectedFloor) { "Recording scope mismatch" }
        UUID.fromString(recordingId)
        require(!submitted || uploadAttempted)
        RoutePlaceEdits.restore(samples,places,frozen = uploadAttempted)
    }
    fun withPlaces(updated: List<RecordedRoutePlace>,nextRevision: Long): SavedWalkingDraft {
        check(!uploadAttempted && !submitted) { "Places are frozen after an upload attempt" }
        require(nextRevision > revision) { "Advance the checkpoint revision" }
        return copy(places = updated.toList(),formatVersion = 2,revision = nextRevision).also {
            it.validateFor(account,buildingId,floorKey)
        }
    }
    internal fun requireSuccessorOf(previous: SavedWalkingDraft) {
        check(recordingId == previous.recordingId) { "Another unfinished local recording already exists" }
        if (revision >= previous.revision) {
            check(formatVersion >= previous.formatVersion) { "Cannot downgrade a checkpoint containing place data" }
            check(!previous.uploadAttempted || (uploadAttempted && samples == previous.samples && places == previous.places)) { "Uploaded geometry and places are frozen" }
            check(!previous.submitted || submitted)
        }
    }
}
