package se.lixo.app.ios

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import dev.gitlive.firebase.auth.ActionCodeSettings
import kotlinx.coroutines.runBlocking
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.ContentMetaResult
import se.lixo.app.data.remote.LexiconMeta
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.HomeworkTask

/**
 * iOS copy of `androidApp/screenshots/DummyData.kt`'s `FakeCloudGateway`: never touches real
 * Firebase. `FirebaseApp.configure()` never runs in the iosTest binary, so any real `dev.gitlive`
 * call would raise an ObjC exception and crash the process -- iosTest code must never construct
 * `FirebaseSyncService`.
 */
class IosFakeCloudGateway : CloudSyncGateway {
    override suspend fun fetchRemoteCourses(): List<Course> = emptyList()
    override suspend fun fetchLevelsForCourse(courseId: String): List<CampaignLevel> = emptyList()
    override suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask> = emptyList()
    override suspend fun fetchLexiconMeta(): LexiconMeta? = null
    override suspend fun fetchLexiconEntries(chunkCount: Int): List<DictionaryEntry> = emptyList()
    override suspend fun fetchContentMeta(): ContentMetaResult? = null
    override suspend fun fetchContentChunks(schema: Int, version: Int, count: Int): List<String>? = null
    override fun buildEmailLinkActionCodeSettings(): ActionCodeSettings = error("unused")
    override suspend fun sendSignInLinkToEmail(email: String): Result<Unit> = Result.success(Unit)
    override fun isSignInWithEmailLink(link: String): Boolean = false
    override suspend fun signInWithEmailLink(email: String, link: String): Result<String> = Result.success("ios_test")
    override suspend fun registerOptionalEmailAccount(email: String, password: String): Result<String> =
        Result.success("ios_test")

    override suspend fun deleteAccountAndAllCloudData(userId: String): Result<Unit> = Result.success(Unit)
    override fun setCrashlyticsCollectionEnabled(enabled: Boolean) = Unit
    override suspend fun ensureSignedIn(): Result<String> = Result.success("ios_test")
}

/** The real bundled content pack, loaded once per test process. */
object IosTestContent {
    val repository by lazy { runBlocking { ContentRepository.loadBundled(isDebugBuild = false) } }
}

/**
 * Builds one "device" [LixoRepository] for iosTest, never touching real Firebase: [lexiconCache]
 * and [profileStore] are explicitly null (their defaults call `createPlatformLexiconCache()` /
 * `createPlatformProfileStore()`, which would try to open the real platform DB).
 */
fun iosRepo(
    uid: String = "ios_test",
    store: FakeFriendCloudStore = FakeFriendCloudStore(),
    createProfile: Boolean = false
): LixoRepository {
    val gateway = object : CloudSyncGateway by IosFakeCloudGateway() {
        override suspend fun ensureSignedIn(): Result<String> = Result.success(uid)
    }
    val repo = LixoRepository(
        gateway,
        friendCloud = FakeFriendCloud(uid, store),
        clockMillis = { 0L },
        lexiconCache = null,
        isDebugBuild = false,
        profileStore = null,
        content = IosTestContent.repository
    )
    if (createProfile) {
        repo.createProfile("Child_$uid", 8)
    }
    return repo
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.hasNodeWithText(text: String): Boolean =
    onAllNodesWithText(text, substring = false, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
