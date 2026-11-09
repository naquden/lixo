package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import se.lixo.app.data.local.CachedWardrobeVersion
import se.lixo.app.data.local.WardrobeCatalogCache
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.LexiconMeta
import se.lixo.app.data.remote.WardrobeMeta
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.engine.RemoteWardrobeValidator
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.ItemPattern
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.TaskType
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.ui.screens.wardrobe.rarityCollectionCounts

private fun remoteItem(
    id: String = "item_hat_remote_test_kosmisk",
    styleIndex: Int = 3,
    minAppVersionCode: Int = 0,
    retired: Boolean = false,
    unlockAchievementId: String? = null,
    unlockStarRequirement: Int = 0,
    isWeeklyStreakSetPiece: Boolean = false,
    slot: EquipmentSlot = EquipmentSlot.HAT
): WardrobeItem = WardrobeItem(
    id = id,
    nameSv = "Kosmisk Testhatt",
    descriptionSv = "En testitem för remote wardrobe-synk.",
    slot = slot,
    rarity = Rarity.LEGENDARY,
    subjectTheme = Subject.ENGLISH,
    meshAssetPath = "3d_sample.glb",
    meshNodeName = "socket_hat_test_kosmisk",
    scaleMultiplier = 1.15f,
    unlockStarRequirement = unlockStarRequirement,
    unlockAchievementId = unlockAchievementId,
    isWeeklyStreakSetPiece = isWeeklyStreakSetPiece,
    primaryColorHex = 0xFF0B0033,
    styleIndex = styleIndex,
    secondaryColorHex = 0xFF00E0FF,
    pattern = ItemPattern.STARS,
    ornament = ItemOrnament.SPARKLE,
    retired = retired,
    minAppVersionCode = minAppVersionCode
)

private fun raw(item: WardrobeItem): String = Json.encodeToString(item)

private class FakeWardrobeCache(
    var version: Int? = null,
    var appVersionCode: Int = 0,
    var items: Map<String, WardrobeItem> = emptyMap()
) : WardrobeCatalogCache {
    var upsertCalls = 0

    override suspend fun loadVersion(): CachedWardrobeVersion? = version?.let { CachedWardrobeVersion(it, appVersionCode) }
    override suspend fun loadItems(): List<WardrobeItem> = items.values.toList()
    override suspend fun upsert(version: Int, appVersionCode: Int, items: List<WardrobeItem>) {
        upsertCalls++
        this.version = version
        this.appVersionCode = appVersionCode
        this.items = this.items + items.associateBy { it.id }
    }
}

private fun gatewayWithWardrobe(
    meta: WardrobeMeta?,
    chunks: List<String> = emptyList()
): CloudSyncGateway = object : CloudSyncGateway by FirebaseSyncService() {
    override suspend fun fetchRemoteCourses(): List<Course> = emptyList()
    override suspend fun fetchLexiconMeta(): LexiconMeta? = null
    override suspend fun fetchWardrobeMeta(): WardrobeMeta? = meta
    override suspend fun fetchWardrobeItems(chunkCount: Int): List<String> = chunks
}

class RemoteWardrobeTest {

    @Test
    fun validator_acceptsValidLegendary() {
        val item = remoteItem()
        val bundledIds = LixoRepository.buildSeedWardrobeCatalog().map { it.id }.toSet()
        val result = RemoteWardrobeValidator.accept(listOf(raw(item)), bundledIds, appVersionCode = 1)
        assertEquals(listOf(item), result)
    }

    @Test
    fun validator_rejectsBundledIdClash() {
        val bundledId = LixoRepository.buildSeedWardrobeCatalog().first().id
        val item = remoteItem(id = bundledId)
        val bundledIds = LixoRepository.buildSeedWardrobeCatalog().map { it.id }.toSet()
        val result = RemoteWardrobeValidator.accept(listOf(raw(item)), bundledIds, appVersionCode = 1)
        assertTrue(result.isEmpty())
    }

    @Test
    fun validator_rejectsStyleIndex9() {
        val item = remoteItem(styleIndex = 9)
        val result = RemoteWardrobeValidator.accept(listOf(raw(item)), emptySet(), appVersionCode = 1)
        assertTrue(result.isEmpty())
    }

    @Test
    fun validator_skipsUnknownSlotButKeepsOthers() {
        val goodItem = remoteItem(id = "item_hat_remote_good")
        val badRaw = raw(remoteItem(id = "item_hat_remote_bad")).replace("\"HAT\"", "\"CAPE\"")
        val result = RemoteWardrobeValidator.accept(listOf(badRaw, raw(goodItem)), emptySet(), appVersionCode = 1)
        assertEquals(listOf(goodItem), result)
    }

    @Test
    fun validator_rejectsAchievementLinked() {
        val item = remoteItem(unlockAchievementId = "ach_x")
        val result = RemoteWardrobeValidator.accept(listOf(raw(item)), emptySet(), appVersionCode = 1)
        assertTrue(result.isEmpty())
    }

    @Test
    fun validator_rejectsSetPiece() {
        val item = remoteItem(isWeeklyStreakSetPiece = true)
        val result = RemoteWardrobeValidator.accept(listOf(raw(item)), emptySet(), appVersionCode = 1)
        assertTrue(result.isEmpty())
    }

    @Test
    fun validator_rejectsStarCurveItem() {
        val item = remoteItem(unlockStarRequirement = 5)
        val result = RemoteWardrobeValidator.accept(listOf(raw(item)), emptySet(), appVersionCode = 1)
        assertTrue(result.isEmpty())
    }

    @Test
    fun validator_rejectsTooNewMinAppVersion() {
        val item = remoteItem(minAppVersionCode = 6)
        val result = RemoteWardrobeValidator.accept(listOf(raw(item)), emptySet(), appVersionCode = 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun validator_duplicateId_firstWins() {
        val first = remoteItem(id = "item_hat_remote_dup", styleIndex = 1)
        val second = remoteItem(id = "item_hat_remote_dup", styleIndex = 2)
        val result = RemoteWardrobeValidator.accept(listOf(raw(first), raw(second)), emptySet(), appVersionCode = 1)
        assertEquals(listOf(first), result)
    }

    @Test
    fun validator_skipsMalformedJson() {
        val good = remoteItem()
        val result = RemoteWardrobeValidator.accept(listOf("{not json", raw(good)), emptySet(), appVersionCode = 1)
        assertEquals(listOf(good), result)
    }

    @Test
    fun sync_newerVersion_addsItemToCatalog() = runTest {
        val item = remoteItem()
        val cache = FakeWardrobeCache()
        val gw = gatewayWithWardrobe(WardrobeMeta(version = 1, chunkCount = 1), listOf(raw(item)))
        val repo = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)

        repo.syncCurriculumFromCloud()

        assertTrue(repo.wardrobeCatalog.value.any { it.id == item.id })
        assertEquals(1, cache.upsertCalls)
    }

    @Test
    fun sync_sameVersion_skipsItemFetch() = runTest {
        val item = remoteItem()
        val cache = FakeWardrobeCache(version = 2, appVersionCode = 1, items = mapOf(item.id to item))
        var fetchCount = 0
        val gw = object : CloudSyncGateway by FirebaseSyncService() {
            override suspend fun fetchRemoteCourses(): List<Course> = emptyList()
            override suspend fun fetchLexiconMeta(): LexiconMeta? = null
            override suspend fun fetchWardrobeMeta(): WardrobeMeta? = WardrobeMeta(version = 2, chunkCount = 1)
            override suspend fun fetchWardrobeItems(chunkCount: Int): List<String> {
                fetchCount++
                return listOf(raw(item))
            }
        }
        val repo = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)

        repo.loadCachedWardrobe()
        repo.syncCurriculumFromCloud()

        assertEquals(0, fetchCount)
    }

    @Test
    fun sync_appUpdate_refetchesSameRemoteVersion() = runTest {
        val item = remoteItem(minAppVersionCode = 20)
        val cache = FakeWardrobeCache()
        var fetchCount = 0
        val gw = object : CloudSyncGateway by FirebaseSyncService() {
            override suspend fun fetchRemoteCourses(): List<Course> = emptyList()
            override suspend fun fetchLexiconMeta(): LexiconMeta? = null
            override suspend fun fetchWardrobeMeta(): WardrobeMeta? = WardrobeMeta(5, 1)
            override suspend fun fetchWardrobeItems(chunkCount: Int): List<String> {
                fetchCount++
                return listOf(raw(item))
            }
        }

        val repo19 = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 19)
        repo19.loadCachedWardrobe()
        repo19.syncCurriculumFromCloud()
        assertFalse(repo19.wardrobeCatalog.value.any { it.id == item.id })
        assertEquals(1, fetchCount)
        assertEquals(5, cache.version)
        assertEquals(19, cache.appVersionCode)

        val repo20 = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 20)
        repo20.loadCachedWardrobe()
        repo20.syncCurriculumFromCloud()
        assertEquals(2, fetchCount)
        assertTrue(repo20.wardrobeCatalog.value.any { it.id == item.id })
        assertEquals(20, cache.appVersionCode)

        val repo20b = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 20)
        repo20b.loadCachedWardrobe()
        repo20b.syncCurriculumFromCloud()
        assertEquals(2, fetchCount)
        assertTrue(repo20b.wardrobeCatalog.value.any { it.id == item.id })
    }

    @Test
    fun sync_offline_noChangeNoCrash() = runTest {
        val cache = FakeWardrobeCache()
        val gw = gatewayWithWardrobe(meta = null)
        val repo = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)

        val bundled = LixoRepository.buildSeedWardrobeCatalog()
        repo.syncCurriculumFromCloud()

        assertEquals(bundled, repo.wardrobeCatalog.value)
    }

    @Test
    fun sync_upsertOnly_itemMissingFromV2Stays() = runTest {
        val itemA = remoteItem(id = "item_hat_remote_a")
        val itemB = remoteItem(id = "item_hat_remote_b")
        val cache = FakeWardrobeCache()
        var version = 1
        var chunks = listOf(raw(itemA))
        val gw = object : CloudSyncGateway by FirebaseSyncService() {
            override suspend fun fetchRemoteCourses(): List<Course> = emptyList()
            override suspend fun fetchLexiconMeta(): LexiconMeta? = null
            override suspend fun fetchWardrobeMeta(): WardrobeMeta? = WardrobeMeta(version, 1)
            override suspend fun fetchWardrobeItems(chunkCount: Int): List<String> = chunks
        }
        val repo = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)
        repo.syncCurriculumFromCloud()
        assertTrue(repo.wardrobeCatalog.value.any { it.id == itemA.id })

        version = 2
        chunks = listOf(raw(itemB))
        repo.syncCurriculumFromCloud()

        assertTrue(repo.wardrobeCatalog.value.any { it.id == itemA.id })
        assertTrue(repo.wardrobeCatalog.value.any { it.id == itemB.id })
    }

    @Test
    fun sync_retiredItem_staysInCatalogButNotChestEligible() = runTest {
        val item = remoteItem(retired = true)
        val cache = FakeWardrobeCache()
        val gw = gatewayWithWardrobe(WardrobeMeta(1, 1), listOf(raw(item)))
        val repo = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)
        repo.syncCurriculumFromCloud()

        assertTrue(repo.wardrobeCatalog.value.any { it.id == item.id })
        assertFalse(ProgressionAndGiftEngine.isChestEligible(item))

        val catalog = repo.wardrobeCatalog.value
        val ownedExceptRetired = catalog.filter { ProgressionAndGiftEngine.isChestEligible(it) }.map { it.id }.toSet()
        val outcome = ProgressionAndGiftEngine().rollChest(catalog, ownedExceptRetired)
        assertNull(outcome?.newlyUnlockedItem)
    }

    @Test
    fun cache_coldStart_restoresItem() = runTest {
        val item = remoteItem()
        val cache = FakeWardrobeCache()
        val gw = gatewayWithWardrobe(WardrobeMeta(1, 1), listOf(raw(item)))
        val repo1 = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)
        repo1.syncCurriculumFromCloud()
        assertTrue(cache.upsertCalls > 0)

        val offlineGw = gatewayWithWardrobe(meta = null)
        val repo2 = LixoRepository(offlineGw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)
        repo2.loadCachedWardrobe()

        assertTrue(repo2.wardrobeCatalog.value.any { it.id == item.id })
    }

    @Test
    fun levelRewards_stableAcrossRemoteSync() = runTest {
        val gw = object : CloudSyncGateway by FirebaseSyncService() {
            override suspend fun fetchRemoteCourses(): List<Course> = listOf(
                Course(
                    id = "course_sv_1",
                    title = "Svenska Sagor: Viskande Skogen",
                    subject = Subject.SWEDISH,
                    minGrade = 1,
                    version = 2,
                    updatedAt = 1793558400000L
                )
            )

            override suspend fun fetchLevelsForCourse(courseId: String): List<CampaignLevel> = listOf(
                CampaignLevel(
                    id = "level_1",
                    courseId = "course_sv_1",
                    order = 1,
                    title = "Bana 1",
                    biomeNameSv = "Viskande Skogen",
                    subject = Subject.SWEDISH,
                    difficulty = 1,
                    timeRequirementSeconds = 55,
                    referenceWpm = 40,
                    taskId = "task_1"
                ),
                CampaignLevel(
                    id = "level_161",
                    courseId = "course_sv_1",
                    order = 161,
                    title = "Bana 161",
                    biomeNameSv = "Viskande Skogen",
                    subject = Subject.SWEDISH,
                    difficulty = 1,
                    timeRequirementSeconds = 55,
                    referenceWpm = 40,
                    taskId = "task_161"
                )
            )

            override suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask> = listOf(
                HomeworkTask(
                    id = "task_1",
                    levelId = "level_1",
                    type = TaskType.READING,
                    subject = Subject.SWEDISH,
                    textContent = "Katten Lixo smyger över den gamla träbron i skogen.",
                    rewardItemId = "item_hat_2"
                ),
                HomeworkTask(
                    id = "task_161",
                    levelId = "level_161",
                    type = TaskType.READING,
                    subject = Subject.SWEDISH,
                    textContent = "En ny saga om Lixo.",
                    rewardItemId = "item_hat_2"
                )
            )

            override suspend fun fetchLexiconMeta(): LexiconMeta? = null
            override suspend fun fetchWardrobeMeta(): WardrobeMeta? = WardrobeMeta(1, 1)
            override suspend fun fetchWardrobeItems(chunkCount: Int): List<String> = listOf(
                raw(remoteItem(id = "item_hat_remote_common", styleIndex = 4)),
                raw(
                    WardrobeItem(
                        id = "item_hat_remote_legendary2",
                        nameSv = "Andra Hatten",
                        descriptionSv = "Ännu en testhatt.",
                        slot = EquipmentSlot.HAT,
                        rarity = Rarity.COMMON,
                        subjectTheme = Subject.ENGLISH,
                        meshAssetPath = "3d_sample.glb",
                        meshNodeName = "socket_hat_remote2",
                        primaryColorHex = 0xFF112233,
                        styleIndex = 5,
                        secondaryColorHex = 0xFF445566
                    )
                )
            )
        }

        val repo = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = null, appVersionCode = 1)
        val seededTask1RewardItemId = repo.tasksById.value.getValue("task_1").rewardItemId

        repo.syncCurriculumFromCloud()

        val task1 = repo.tasksById.value.getValue("task_1")
        assertEquals(seededTask1RewardItemId, task1.rewardItemId)

        val task161 = repo.tasksById.value.getValue("task_161")
        val expectedPool = LixoRepository.buildLevelRewardPool(LixoRepository.buildSeedWardrobeCatalog())
        assertEquals(LixoRepository.levelRewardItemIdFor(161, expectedPool), task161.rewardItemId)
    }

    @Test
    fun chestPool_includesRemoteLegendary() = runTest {
        val item = remoteItem()
        val cache = FakeWardrobeCache()
        val gw = gatewayWithWardrobe(WardrobeMeta(1, 1), listOf(raw(item)))
        val repo = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)
        repo.syncCurriculumFromCloud()

        val catalog = repo.wardrobeCatalog.value
        val owned = catalog.filter { ProgressionAndGiftEngine.isChestEligible(it) && it.id != item.id }.map { it.id }.toSet()
        val outcome = assertNotNull(ProgressionAndGiftEngine().rollChest(catalog, owned, chestsSinceLegendary = 19))
        assertEquals(item.id, outcome.rolledItem.id)
    }

    @Test
    fun rarityCounts_includeRemoteAndHideRetiredUnowned() = runTest {
        val activeItem = remoteItem(id = "item_hat_remote_active")
        val retiredItem = remoteItem(id = "item_hat_remote_retired", retired = true)
        val cache = FakeWardrobeCache()
        val gw = gatewayWithWardrobe(WardrobeMeta(1, 1), listOf(raw(activeItem), raw(retiredItem)))
        val repo = LixoRepository(gw, lexiconCache = null, isDebugBuild = false, profileStore = null, wardrobeCache = cache, appVersionCode = 1)
        repo.syncCurriculumFromCloud()

        val catalog = repo.wardrobeCatalog.value
        assertTrue(catalog.any { it.id == activeItem.id })
        assertTrue(catalog.any { it.id == retiredItem.id })

        val countsWithoutOwningRetired = rarityCollectionCounts(catalog, unlockedItemIds = setOf(activeItem.id))
        val legendaryCountUnowned = countsWithoutOwningRetired.first { it.rarity == Rarity.LEGENDARY }

        val countsOwningRetiredToo = rarityCollectionCounts(
            catalog,
            unlockedItemIds = setOf(activeItem.id, retiredItem.id)
        )
        val legendaryCountOwned = countsOwningRetiredToo.first { it.rarity == Rarity.LEGENDARY }

        // Owning the retired item adds 1 to both owned and total; not owning it must hide it from total.
        assertEquals(legendaryCountUnowned.total + 1, legendaryCountOwned.total)
        assertEquals(legendaryCountUnowned.owned + 1, legendaryCountOwned.owned)
    }
}
