package com.intramap.ui.navigation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.navigation.FloorTravelMode
import com.intramap.navigation.NavigationSession

@Composable
fun FloorChangeControls(
    session: NavigationSession,
    onConfirmArrival: () -> Unit,
    onCorrectLocation: () -> Unit
) {
    val transfer = session.pendingFloorArrival

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Between floors",
            style = MaterialTheme.typography.labelLarge
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloorTravelMode.values().forEach { mode ->
                FilterChip(
                    selected = session.floorTravelMode == mode,
                    enabled = transfer == null &&
                            !(session.stepFree &&
                                    mode == FloorTravelMode.STAIRS),
                    onClick = {
                        session.updateFloorTravelMode(mode)
                    },
                    label = {
                        Text(
                            when (mode) {
                                FloorTravelMode.AUTO -> "Auto"
                                FloorTravelMode.STAIRS -> "Stairs"
                                FloorTravelMode.ELEVATOR -> "Elevator"
                            }
                        )
                    }
                )
            }
        }

        val position = session.estimate
        val next = session.nextNode

        val awaitingTransfer =
            position != null &&
                    next != null &&
                    next.floorId != position.floorId

        if (transfer != null || awaitingTransfer) {
            val target = transfer ?: next

            if (target != null) {
                val floorName = session.building.floors
                    .firstOrNull { it.floorId == target.floorId }
                    ?.floorName
                    ?: target.floorId

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${target.type.lowercase().replaceFirstChar {
                                it.uppercase()
                            }} to $floorName",
                            style = MaterialTheme.typography.titleMedium
                        )

                        if (transfer != null) {
                            Text(
                                "Tracking is paused. Confirm only after " +
                                        "you physically reach $floorName."
                            )

                            Button(
                                onClick = onConfirmArrival,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("I have reached $floorName")
                            }

                            TextButton(
                                onClick = {
                                    session.cancelFloorTransition()
                                }
                            ) {
                                Text("Cancel floor change")
                            }
                        } else {
                            val eligible =
                                session.transitionTarget != null

                            Button(
                                enabled = eligible,
                                onClick = {
                                    session.beginFloorTransition()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Start ${target.type.lowercase()} " +
                                            "to $floorName"
                                )
                            }

                            if (!eligible) {
                                Text(
                                    "Waiting for a reliable position " +
                                            "near the source connector.",
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )

                                TextButton(
                                    onClick = onCorrectLocation
                                ) {
                                    Text("Correct my actual location")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (
            session.ready &&
            session.destination != null &&
            session.route == null
        ) {
            Text(
                "No available route for this combination of " +
                        "travel mode, accessibility and blocked paths. " +
                        "Try Auto or another mapped connector.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}