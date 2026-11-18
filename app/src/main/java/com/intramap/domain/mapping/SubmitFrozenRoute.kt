package com.intramap.domain.mapping

import com.intramap.data.repository.SavedWalkingDraft

/** Caller must have awaited durable checkpointing of this exact frozen snapshot. */
suspend fun submitFrozenRoute(
    draft: SavedWalkingDraft,
    readRemoteStatus: suspend () -> String,
    uploadPoints: suspend () -> Unit,
    finalizePlaces: suspend () -> Unit
) {
    draft.validateFor(draft.account,draft.buildingId,draft.floorKey)
    require(draft.uploadAttempted && draft.samples.size >= 2) { "A durable frozen walking snapshot is required" }
    when (readRemoteStatus()) {
        "draft" -> uploadPoints()
        "submitted","approved" -> Unit
        else -> error("Recording cannot be submitted in its current state")
    }
    finalizePlaces()
}
