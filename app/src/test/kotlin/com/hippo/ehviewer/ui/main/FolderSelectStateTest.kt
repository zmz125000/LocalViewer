package com.hippo.ehviewer.ui.main

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FolderSelectStateTest {
    @Before
    fun resetHost() {
        FolderSelectHost.resetForTest()
    }

    @Test
    fun overflowSelectMarksThatItemAndEntersSelectMode() {
        val state = FolderSelectState()
        state.bindFolder("a")
        state.enter(pick("1", "one"))
        assertTrue(state.selecting)
        assertEquals(1, state.selectionEpoch)
        assertEquals(listOf("1"), state.picks.map { it.key })
        state.enter(pick("1", "one"))
        assertEquals(1, state.picks.size)
        assertEquals(1, state.selectionEpoch)
        state.exitSelectMode()
        state.enter(pick("1", "one"))
        assertEquals(2, state.selectionEpoch)
        state.bindFolder("b")
        state.bindFolder("a")
        assertTrue(state.selecting)
        assertEquals(2, state.selectionEpoch)
    }

    @Test
    fun selectTheseAddsOnlyMissingRows() {
        val state = FolderSelectState()
        state.bindFolder("a")
        val line = BrowseListLine(typeLabel = "JPG", sizeBytes = 2048)
        state.selectThese(listOf(pick("1", "one").copy(listLine = line)))
        assertTrue(state.picks.isEmpty())
        state.enter(pick("1", "one"))
        state.selectThese(
            listOf(
                pick("1", "one").copy(listLine = line),
                pick("2", "two").copy(listLine = line),
            ),
        )
        assertEquals(listOf("1", "2"), state.picks.map { it.key })
        assertEquals("JPG", state.picks[0].listLine?.typeLabel)
        assertEquals(2048L, state.picks[1].listLine?.sizeBytes)
        state.selectSection(BrowseFolderSection.Recent)
        assertEquals(2, state.picks.size)
    }

    @Test
    fun longPressSelectsTheSectionAndPressingAgainClearsIt() {
        val state = FolderSelectState()
        state.bindFolder("a")
        state.enter(pick("9", "other"))
        val files = listOf(pick("1", "one"), pick("2", "two"))
        state.sectionSelect = mapOf(BrowseFolderSection.Files to { files })
        state.selectSection(BrowseFolderSection.Files)
        assertEquals(listOf("9", "1", "2"), state.picks.map { it.key })
        state.selectSection(BrowseFolderSection.Files)
        assertEquals(listOf("9"), state.picks.map { it.key })
        state.toggle(pick("1", "one"))
        state.selectSection(BrowseFolderSection.Files)
        assertEquals(listOf("9", "1", "2"), state.picks.map { it.key })
    }

    @Test
    fun longPressOutsideSelectModeEntersAndSelectsTheSection() {
        val state = FolderSelectState()
        state.bindFolder("a")
        val files = listOf(pick("1", "one"), pick("2", "two"))
        state.sectionSelect = mapOf(BrowseFolderSection.Files to { files })
        state.selectSection(BrowseFolderSection.Files)
        assertTrue(state.selecting)
        assertEquals(1, state.selectionEpoch)
        assertEquals(listOf("1", "2"), state.picks.map { it.key })
        state.exitSelectMode()
        assertFalse(state.selecting)
        state.selectSection(BrowseFolderSection.Files)
        assertTrue(state.selecting)
        assertEquals(2, state.selectionEpoch)
        assertEquals(listOf("1", "2"), state.picks.map { it.key })
    }

    @Test
    fun selectableCopiesTheListLine() {
        val line = BrowseListLine(typeLabel = "PNG", sizeBytes = 4096, pageCount = 3)
        val pick = BrowseOverflowActions(onUnsupported = {})
            .selectable(key = "f", title = "a.png", listLine = line)
            .toPick()
        assertEquals("PNG", pick?.listLine?.typeLabel)
        assertEquals(4096L, pick?.listLine?.sizeBytes)
        assertEquals(3, pick?.listLine?.pageCount)
    }

    @Test
    fun eachFolderKeepsItsOwnSelection() {
        val state = FolderSelectState()
        state.bindFolder("a")
        state.enter(pick("1", "one"))
        state.toggle(pick("2", "two"))
        state.sectionKeys = mapOf(BrowseFolderSection.Files to listOf("1", "2"))

        state.bindFolder("b")
        assertFalse(state.selecting)
        assertTrue(state.picks.isEmpty())
        assertTrue(state.sectionKeys.isEmpty())
        state.enter(pick("9", "nine"))

        state.bindFolder("a")
        assertTrue(state.selecting)
        assertEquals(listOf("1", "2"), state.picks.map { it.key })

        state.bindFolder("b")
        assertTrue(state.selecting)
        assertEquals(listOf("9"), state.picks.map { it.key })
    }

    @Test
    fun cancelClearsOnlyTheCurrentFolder() {
        val state = FolderSelectState()
        state.bindFolder("a")
        state.enter(pick("1", "one"))
        state.bindFolder("b")
        state.enter(pick("9", "nine"))
        state.cancel()
        assertFalse(state.selecting)
        assertTrue(state.picks.isEmpty())

        state.bindFolder("a")
        assertTrue(state.selecting)
        assertEquals(listOf("1"), state.picks.map { it.key })
        state.bindFolder("b")
        assertFalse(state.selecting)
        assertTrue(state.picks.isEmpty())
    }

    @Test
    fun backLeavesSelectModeWithoutClearingTheSnackbar() {
        val state = FolderSelectState()
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.toggle(pick("2", "two"))
        state.exitSelectMode()
        assertFalse(state.selecting)
        assertEquals(listOf("1", "2"), state.picks.map { it.key })
        assertEquals("Photos", FolderSelectHost.selectionSnack?.folderName)
        assertEquals(2, FolderSelectHost.selectionSnack?.count)

        state.bindFolder("b", "Camera")
        assertEquals("Photos", FolderSelectHost.selectionSnack?.folderName)
        assertEquals(2, FolderSelectHost.selectionSnack?.count)
        assertEquals(listOf("1", "2"), FolderSelectHost.selectionPicks.map { it.key })

        val restored = FolderSelectState()
        restored.bindFolder("b", "Camera")
        assertEquals("Photos", FolderSelectHost.selectionSnack?.folderName)

        FolderSelectHost.cancelShownSelection()
        assertNull(FolderSelectHost.selectionSnack)
        assertTrue(FolderSelectHost.selectionSnacks.isEmpty())
        restored.bindFolder("a", "Photos")
        assertTrue(restored.picks.isEmpty())
        assertFalse(restored.selecting)
    }

    @Test
    fun lastRemovedPickClearsTheSnackbar() {
        val state = FolderSelectState()
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.toggle(pick("2", "two"))
        state.toggle(pick("2", "two"))
        assertEquals(1, FolderSelectHost.selectionSnack?.count)
        state.toggle(pick("1", "one"))
        assertTrue(state.selecting)
        assertTrue(state.picks.isEmpty())
        assertNull(FolderSelectHost.selectionSnack)

        state.toggle(pick("1", "one"))
        assertEquals(1, FolderSelectHost.selectionSnack?.count)
        state.sectionSelect = mapOf(
            BrowseFolderSection.Files to { listOf(pick("1", "one")) },
        )
        state.selectSection(BrowseFolderSection.Files)
        assertTrue(state.picks.isEmpty())
        assertNull(FolderSelectHost.selectionSnack)

        state.enter(pick("9", "nine"))
        state.exitSelectMode()
        FolderSelectHost.removeShownPick("9")
        assertNull(FolderSelectHost.selectionSnack)
        assertFalse(state.selecting)
    }

    @Test
    fun taskBarLeavesTheOtherSelectionAndThePanelFollowsTheBar() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.bindFolder("b", "Camera")
        state.enter(pick("9", "nine"))
        val jobs = mutableStateListOf(
            TaskJob(1, "one", false, pick("1", "one"), { true }, TaskStatus.Pending),
        )
        FolderSelectHost.installBatchForTest(
            TaskBatch("a", jobs, kotlinx.coroutines.Job(), "Photos"),
        )
        assertEquals(listOf("Photos", "Camera"), FolderSelectHost.selectionSnacks.map { it.folderName })
        assertEquals(listOf("Camera"), FolderSelectHost.selectionSnacksBesideTask().map { it.folderName })
        assertTrue(FolderSelectHost.showingTask)

        FolderSelectHost.showList("b")
        assertFalse(FolderSelectHost.showingTask)
        assertEquals(listOf("9"), FolderSelectHost.selectionPicks.map { it.key })

        FolderSelectHost.showTaskList()
        assertTrue(FolderSelectHost.showingTask)
        assertEquals("a", FolderSelectHost.batch?.folderKey)

        FolderSelectHost.showList("b")
        FolderSelectHost.cancelSelection("b")
        assertEquals(listOf("Photos"), FolderSelectHost.selectionSnacks.map { it.folderName })
        assertTrue(FolderSelectHost.taskRunning)
        assertFalse(FolderSelectHost.dialog)
    }

    @Test
    fun secondFolderTaskStartsWhenPreviousRunFinishesNotOnDone() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.bindFolder("b", "Camera")
        state.enter(pick("9", "nine"))
        val firstJobs = mutableStateListOf(
            TaskJob(1, "one", false, pick("1", "one"), { true }, TaskStatus.Pending),
        )
        val first = TaskBatch("a", firstJobs, kotlinx.coroutines.Job(), "Photos")
        FolderSelectHost.installBatchForTest(first)

        FolderSelectHost.startTasks(
            listOf(
                TaskJob(2, "nine", false, pick("9", "nine"), { true }, TaskStatus.Pending),
            ),
            "/tmp".toPath(),
            "b",
        )
        assertEquals("a", FolderSelectHost.batch?.folderKey)
        assertEquals(setOf("b"), FolderSelectHost.queuedFolderKeys())
        assertEquals(1, FolderSelectHost.queuedCounts["b"])

        // Finishing the run hands off (Continue) without waiting for Done.
        FolderSelectHost.dialog = false
        FolderSelectHost.completeRunForTest(first)
        assertEquals("b", FolderSelectHost.batch?.folderKey)
        assertTrue(FolderSelectHost.queuedFolderKeys().isEmpty())
        assertFalse(FolderSelectHost.dialog)
        // Previous folder keeps its selection (Continue), not cleared as Done would.
        assertEquals(listOf("Photos"), FolderSelectHost.selectionSnacksBesideTask().map { it.folderName })
    }

    @Test
    fun queueStartsImmediatelyIfPreviousAlreadyFinished() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.bindFolder("b", "Camera")
        state.enter(pick("9", "nine"))
        val first = TaskBatch(
            "a",
            mutableStateListOf(
                TaskJob(1, "one", false, pick("1", "one"), { true }, TaskStatus.Done),
            ),
            kotlinx.coroutines.Job(),
            "Photos",
        )
        first.finishedRun = true
        FolderSelectHost.installBatchForTest(first)
        FolderSelectHost.dialog = false

        FolderSelectHost.startTasks(
            listOf(TaskJob(2, "nine", false, pick("9", "nine"), { true }, TaskStatus.Pending)),
            "/tmp".toPath(),
            "b",
        )
        assertEquals("b", FolderSelectHost.batch?.folderKey)
        assertTrue(FolderSelectHost.queuedFolderKeys().isEmpty())
        assertFalse(FolderSelectHost.dialog)
    }

    @Test
    fun autoFinishDonesWhenTheRunCompletesWithNoQueue() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        FolderSelectHost.autoFinishForTest = true
        FolderSelectHost.startTasks(
            listOf(TaskJob(1, "one", false, pick("1", "one"), { true }, TaskStatus.Pending)),
            "/tmp".toPath(),
            "a",
        )
        assertNull(FolderSelectHost.batch)
        assertFalse(FolderSelectHost.dialog)
        assertTrue(state.picks.isEmpty())
        assertNull(FolderSelectHost.selectionSnack)
    }

    @Test
    fun autoFinishDonesThenStartsTheQueuedBatch() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.bindFolder("b", "Camera")
        state.enter(pick("9", "nine"))
        FolderSelectHost.autoFinishForTest = true
        val first = TaskBatch(
            "a",
            mutableStateListOf(
                TaskJob(1, "one", false, pick("1", "one"), { true }, TaskStatus.Pending),
            ),
            kotlinx.coroutines.Job(),
            "Photos",
        )
        FolderSelectHost.installBatchForTest(first)
        FolderSelectHost.dialog = false
        FolderSelectHost.startTasks(
            listOf(TaskJob(2, "nine", false, pick("9", "nine"), { true }, TaskStatus.Pending)),
            "/tmp".toPath(),
            "b",
        )
        FolderSelectHost.completeRunForTest(first)
        // A is cleared (Done); B is installed next; panel stays closed.
        assertEquals("b", FolderSelectHost.batch?.folderKey)
        assertFalse(FolderSelectHost.dialog)
        state.bindFolder("a", "Photos")
        assertTrue(state.picks.isEmpty())
    }

    @Test
    fun startingATaskDoesNotOpenThePanel() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        FolderSelectHost.dialog = false
        FolderSelectHost.startTasks(
            listOf(
                TaskJob(1, "one", false, pick("1", "one"), {
                    // Keep the batch alive so we can assert before auto-finish.
                    kotlinx.coroutines.CompletableDeferred<Unit>().await()
                    true
                }, TaskStatus.Pending),
            ),
            "/tmp".toPath(),
            "a",
        )
        assertEquals("a", FolderSelectHost.batch?.folderKey)
        assertFalse(FolderSelectHost.dialog)
        assertFalse(FolderSelectHost.showingTask)
    }

    @Test
    fun clearingAQueuedSelectionDropsItsBatch() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.bindFolder("b", "Camera")
        state.enter(pick("9", "nine"))
        FolderSelectHost.installBatchForTest(
            TaskBatch(
                "a",
                mutableStateListOf(
                    TaskJob(1, "one", false, pick("1", "one"), { true }, TaskStatus.Pending),
                ),
                kotlinx.coroutines.Job(),
                "Photos",
            ),
        )
        FolderSelectHost.startTasks(
            listOf(TaskJob(2, "nine", false, pick("9", "nine"), { true }, TaskStatus.Pending)),
            "/tmp".toPath(),
            "b",
        )
        assertEquals(setOf("b"), FolderSelectHost.queuedFolderKeys())
        FolderSelectHost.cancelSelection("b")
        assertTrue(FolderSelectHost.queuedFolderKeys().isEmpty())
        assertEquals("a", FolderSelectHost.batch?.folderKey)
    }

    @Test
    fun eachFolderKeepsItsOwnSnackbar() {
        val state = FolderSelectState()
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.bindFolder("b", "Camera")
        state.enter(pick("9", "nine"))
        assertEquals(listOf("Photos", "Camera"), FolderSelectHost.selectionSnacks.map { it.folderName })

        FolderSelectHost.cancelSelection("a")
        assertEquals(listOf("Camera"), FolderSelectHost.selectionSnacks.map { it.folderName })
        assertEquals(1, FolderSelectHost.selectionSnacks.single().count)
        state.bindFolder("a", "Photos")
        assertTrue(state.picks.isEmpty())
    }

    @Test
    fun pauseLeavesLaterJobsPending() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var second = false
        val jobs = mutableStateListOf(
            TaskJob(1, "one", false, pick("1", "one"), {
                started.complete(Unit)
                release.await()
                true
            }, TaskStatus.Pending),
            TaskJob(2, "two", false, pick("2", "two"), {
                second = true
                true
            }, TaskStatus.Pending),
        )
        val batch = TaskBatch("a", jobs, kotlinx.coroutines.Job())
        val runner = launch(Dispatchers.Default) {
            executeJobs(batch, "/tmp".toPath())
        }
        withTimeout(5_000) { started.await() }
        batch.paused = true
        batch.wake()
        release.complete(Unit)
        withTimeout(5_000) {
            while (jobs[0].status != TaskStatus.Done) delay(10)
        }
        delay(80)
        assertFalse(second)
        assertEquals(TaskStatus.Pending, jobs[1].status)
        batch.paused = false
        batch.wake()
        withTimeout(5_000) { runner.join() }
        assertTrue(second)
        assertEquals(TaskStatus.Done, jobs[1].status)
    }

    @Test
    fun stopReturnsUnfinishedItemsToTheSelection() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        val done = pick("1", "one")
        val pending = pick("2", "two")
        val failed = pick("3", "three")
        val running = pick("4", "four")
        state.enter(done)
        state.toggle(pending)
        state.toggle(failed)
        state.toggle(running)
        val jobs = mutableStateListOf(
            TaskJob(1, done.title, false, done, { true }, TaskStatus.Done),
            TaskJob(2, pending.title, false, pending, { true }, TaskStatus.Pending),
            TaskJob(3, failed.title, false, failed, { true }, TaskStatus.Failed),
            TaskJob(4, running.title, false, running, { true }, TaskStatus.Running),
        )
        val batch = TaskBatch("a", jobs, kotlinx.coroutines.Job())
        batch.end = BatchEnd.Stop
        FolderSelectHost.dialog = true
        FolderSelectHost.endBatchForTest(batch)
        assertNull(FolderSelectHost.batch)
        assertTrue(FolderSelectHost.dialog)
        assertEquals(listOf("2", "3", "4"), state.picks.map { it.key })
    }

    @Test
    fun continueKeepsTheSelectionAndDoneClearsIt() {
        val state = FolderSelectState()
        FolderSelectHost.attach(state)
        state.bindFolder("a", "Photos")
        val item = pick("1", "one")
        state.enter(item)
        val jobs = mutableStateListOf(
            TaskJob(1, item.title, false, item, { true }, TaskStatus.Done),
        )
        val staying = TaskBatch("a", jobs, kotlinx.coroutines.Job())
        staying.end = BatchEnd.Continue
        FolderSelectHost.dialog = false
        FolderSelectHost.endBatchForTest(staying)
        // Continue keeps the selection; the panel opens only via continueSelection().
        assertFalse(FolderSelectHost.dialog)
        assertEquals(listOf("1"), state.picks.map { it.key })
        assertEquals(1, FolderSelectHost.selectionSnack?.count)

        val closing = TaskBatch("a", jobs, kotlinx.coroutines.Job())
        closing.end = BatchEnd.Done
        FolderSelectHost.endBatchForTest(closing)
        assertFalse(FolderSelectHost.dialog)
        assertTrue(state.picks.isEmpty())
        assertNull(FolderSelectHost.selectionSnack)
    }

    private fun pick(key: String, title: String) = FolderPick(
        key = key,
        title = title,
        shareHttp = null,
        convertStill = null,
        saveInto = null,
    )
}
