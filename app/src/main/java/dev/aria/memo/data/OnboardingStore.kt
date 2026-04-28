package dev.aria.memo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single-bit DataStore tracking whether the user has been through the
 * first-launch onboarding flow.
 *
 * Fixes #144 (Bug-2 Critical#1): the very first thing a fresh installer
 * saw was an empty notes tab and a FAB. Tapping the FAB to write a note
 * crashed into a "PAT not configured" Snackbar that erased the draft.
 * The onboarding sheet now greets that user, explains the GitHub-backed
 * model, and routes them to Settings before they lose work.
 *
 * The flag is intentionally not synced to GitHub — it tracks per-device
 * "I've seen the intro screen", not "I've finished setup". A user who
 * dismisses without configuring still has [completed] = true; the empty
 * state on the notes tab is what nudges them after that.
 */
private val Context.onboardingDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "memo_onboarding")

class OnboardingStore(private val context: Context) {

    private object Keys {
        val COMPLETED = booleanPreferencesKey("completed")
    }

    val completed: Flow<Boolean> = context.onboardingDataStore.data
        .map { it[Keys.COMPLETED] ?: false }

    suspend fun markCompleted() {
        context.onboardingDataStore.edit { it[Keys.COMPLETED] = true }
    }

    /** Test affordance: re-open the flow on demand. Not surfaced in UI. */
    suspend fun reset() {
        context.onboardingDataStore.edit { it[Keys.COMPLETED] = false }
    }
}
