package com.company.skolab.viewmodel

import app.cash.turbine.test
import com.company.skolab.model.IndustryOpportunity
import com.company.skolab.model.OpportunityType
import com.company.skolab.model.PositionLevel
import com.company.skolab.model.RemoteType
import com.company.skolab.repository.IndustryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IndustryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: IndustryRepository
    private lateinit var vm: IndustryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = mockk(relaxed = true)
        vm = IndustryViewModel(repository = mockRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleOpp(id: String = "opp_1", title: String = "ML Research Scientist") =
        IndustryOpportunity(
            id = id,
            type = OpportunityType.JOB,
            title = title,
            companyOrFunder = "MIT CSAIL",
            description = "Research in machine learning and neural networks",
            requiredSkills = listOf("PyTorch", "Machine Learning"),
            tags = listOf("ML", "Research"),
            location = "Cambridge, MA",
            positionLevel = PositionLevel.POSTDOC,
            remoteType = RemoteType.ON_SITE
        )

    // ── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial opportunities state is empty`() = runTest {
        assertEquals(emptyList<IndustryOpportunity>(), vm.opportunities.value)
    }

    @Test
    fun `initial loading state is false`() = runTest {
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `initial error state is null`() = runTest {
        assertNull(vm.error.value)
    }

    @Test
    fun `initial bookmarkedIds is empty`() = runTest {
        assertTrue(vm.bookmarkedIds.value.isEmpty())
    }

    // ── setError ─────────────────────────────────────────────────────────────

    @Test
    fun `setError updates error state`() = runTest {
        vm.setError("Something went wrong")
        assertEquals("Something went wrong", vm.error.value)
    }

    @Test
    fun `setError with null clears error state`() = runTest {
        vm.setError("error")
        vm.setError(null)
        assertNull(vm.error.value)
    }

    // ── loadOpportunities ────────────────────────────────────────────────────

    @Test
    fun `loadOpportunities with blank focus sets error without loading`() = runTest {
        vm.loadOpportunities("  ")
        assertFalse("Should not enter loading state for blank focus", vm.isLoading.value)
        assertNotNull(vm.error.value)
        assertTrue(vm.error.value!!.contains("focus", ignoreCase = true))
        coVerify(exactly = 0) { mockRepository.getOpportunities(any(), any()) }
    }

    @Test
    fun `loadOpportunities success populates opportunities StateFlow`() = runTest {
        val opps = listOf(sampleOpp("1"), sampleOpp("2"))
        coEvery { mockRepository.getOpportunities("ml", null) } returns opps

        vm.opportunities.test {
            assertEquals(emptyList<IndustryOpportunity>(), awaitItem())  // initial
            vm.loadOpportunities("ml")
            advanceUntilIdle()
            assertEquals(opps, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadOpportunities clears error on success`() = runTest {
        vm.setError("stale error")
        coEvery { mockRepository.getOpportunities("ml", null) } returns emptyList()

        vm.loadOpportunities("ml")
        advanceUntilIdle()

        assertNull(vm.error.value)
    }

    @Test
    fun `loadOpportunities exception sets error state`() = runTest {
        coEvery { mockRepository.getOpportunities(any(), any()) } throws RuntimeException("network error")

        vm.loadOpportunities("ml")
        advanceUntilIdle()

        assertNotNull(vm.error.value)
        assertTrue(vm.error.value!!.contains("network error", ignoreCase = true))
        assertFalse("isLoading should be false after error", vm.isLoading.value)
    }

    @Test
    fun `loadOpportunities does not double-load when already loading`() = runTest {
        coEvery { mockRepository.getOpportunities(any(), any()) } returns emptyList()

        vm.loadOpportunities("ml")
        vm.loadOpportunities("ml")  // second call while first is pending
        advanceUntilIdle()

        coVerify(exactly = 1) { mockRepository.getOpportunities("ml", null) }
    }

    // ── loadRoadmap ───────────────────────────────────────────────────────────

    @Test
    fun `loadRoadmap with blank focus sets error`() = runTest {
        vm.loadRoadmap("uid", "Alice", "  ")
        assertNotNull(vm.error.value)
        coVerify(exactly = 0) { mockRepository.getRoadmap(any(), any(), any()) }
    }

    @Test
    fun `loadRoadmap null result keeps roadmap null`() = runTest {
        coEvery { mockRepository.getRoadmap(any(), any(), any()) } returns null

        vm.loadRoadmap("uid", "Alice", "bioinformatics")
        advanceUntilIdle()

        assertNull(vm.roadmap.value)
        assertFalse(vm.isLoadingRoadmap.value)
    }

    // ── computeMatchScore ────────────────────────────────────────────────────

    @Test
    fun `computeMatchScore returns value in 52-99 range`() = runTest {
        val score = vm.computeMatchScore("machine learning neural networks", sampleOpp())
        assertTrue("Score $score out of expected range", score in 52..99)
    }

    @Test
    fun `computeMatchScore with blank focus returns 65`() = runTest {
        assertEquals(65, vm.computeMatchScore("", sampleOpp()))
    }

    @Test
    fun `computeMatchScore is deterministic`() = runTest {
        val focus = "deep learning transformers"
        val opp = sampleOpp()
        assertEquals(vm.computeMatchScore(focus, opp), vm.computeMatchScore(focus, opp))
    }

    // ── AI draft state ───────────────────────────────────────────────────────

    @Test
    fun `clearAiDraft resets draft and error to null`() = runTest {
        vm.clearAiDraft()
        assertNull(vm.aiDraft.value)
        assertNull(vm.aiDraftError.value)
        assertFalse(vm.isGeneratingDraft.value)
    }

    // ── Bookmark (in-memory path — no DataStore context) ─────────────────────

    @Test
    fun `toggleBookmark without initBookmarks is a no-op`() = runTest {
        vm.toggleBookmark("opp_1")
        // bookmarkDataStore is null, so ids remain empty
        assertTrue(vm.bookmarkedIds.value.isEmpty())
    }

    @Test
    fun `bookmarkedIds StateFlow emits empty set initially`() = runTest {
        vm.bookmarkedIds.test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Model defaults ────────────────────────────────────────────────────────

    @Test
    fun `IndustryOpportunity default values for new fields`() {
        val opp = IndustryOpportunity(id = "x", type = OpportunityType.JOB,
            title = "T", companyOrFunder = "Org")
        assertEquals("", opp.location)
        assertEquals(PositionLevel.UNSPECIFIED, opp.positionLevel)
        assertEquals(RemoteType.UNSPECIFIED, opp.remoteType)
    }

    @Test
    fun `PositionLevel displayLabel returns empty string for UNSPECIFIED`() {
        assertEquals("", PositionLevel.UNSPECIFIED.displayLabel())
    }

    @Test
    fun `RemoteType displayLabel returns correct labels`() {
        assertEquals("On-site", RemoteType.ON_SITE.displayLabel())
        assertEquals("Remote", RemoteType.REMOTE.displayLabel())
        assertEquals("Hybrid", RemoteType.HYBRID.displayLabel())
        assertEquals("", RemoteType.UNSPECIFIED.displayLabel())
    }
}
