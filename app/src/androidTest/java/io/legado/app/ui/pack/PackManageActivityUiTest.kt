package io.legado.app.ui.pack

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.help.pack.PackHost
import io.legado.app.ui.file.HandleFileActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 扩展包管理界面的用户操作验证(以 mock 包为准, 不依赖任何真实平台):
 * * 空列表状态与列表/管理器数据一致;
 * * 本地文件导入: 先出现确认对话框, 未确认前不注册;
 * * 写工具默认不勾选, network=open 未同意时无法安装;
 * * 损坏文件的红牌错误以可见方式回传;
 * * 同包升级权限变化时高亮差异并要求重新确认。
 *
 * 依赖集成阶段的 :modules:pack 依赖与 PackHost/DefaultPackManager 实现。
 */
@RunWith(AndroidJUnit4::class)
class PackManageActivityUiTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "pack-ui-$id")

    @Before
    fun setUp() {
        directory.mkdirs()
    }

    @After
    fun tearDown() {
        // 撤销本用例注册的 mock 包, 避免污染宿主
        runBlocking {
            PackHost.manager.list()
                .filter { it.manifest.id.value.startsWith("uitest.") }
                .forEach { PackHost.manager.uninstall(it.ref.id) }
        }
        directory.deleteRecursively()
    }

    private val singleFilePack = """
        // ==Pack==
        // @id uitest.hello
        // @name UI Test Hello
        // @version 1.0.0
        // @versionCode 1
        // @packLib >=1.0.0 <2.0.0
        // @capability SOURCE_COMMANDS
        // @command hello source
        // ==/Pack==

        function hello() { return "hello"; }
    """.trimIndent() + "\n"

    private fun toolsPackJson(
        versionCode: Long,
        version: String,
        network: String?,
        tools: String,
    ): String {
        val fields = mutableListOf<String>()
        fields += "\"schemaVersion\":1"
        fields += "\"id\":\"uitest.tools\""
        fields += "\"name\":\"UI Test Tools\""
        fields += "\"version\":\"$version\""
        fields += "\"versionCode\":$versionCode"
        fields += "\"packLib\":\">=1.0.0 <2.0.0\""
        fields += "\"capabilities\":[\"SOURCE_COMMANDS\",\"MCP_TOOLS\"]"
        fields += "\"components\":[{\"id\":\"main\",\"type\":\"js\",\"entry\":\"main.js\"}]"
        fields += "\"commands\":[{\"name\":\"hello\",\"component\":\"main\",\"source\":true}]"
        network?.let { fields += "\"network\":$it" }
        fields += "\"mcpTools\":$tools"
        return fields.joinToString(separator = ",", prefix = "{", postfix = "}")
    }

    private fun writeZip(file: File, manifestJson: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifestJson.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("main.js"))
            zip.write("function hello() { return \"hello\"; }\n".toByteArray())
            zip.closeEntry()
        }
    }

    @Test(timeout = 120_000)
    fun emptyOrCurrentListMatchesTheManagerStateAndShowsTheEmptyHintWhenEmpty() {
        ActivityScenario.launch<PackManageActivity>(
            Intent(context, PackManageActivity::class.java)
        ).use { scenario ->
            awaitPacksLoaded(scenario)
            val installed = PackHost.manager.list()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[PackManageViewModel::class.java]
                assertEquals(installed, model.packsLiveData.value)
            }
            if (installed.isEmpty()) {
                onView(withId(R.id.ll_empty)).check(matches(isDisplayed()))
                onView(withText(R.string.pack_manage_empty)).check(matches(isDisplayed()))
            } else {
                onView(withId(R.id.recycler_view)).check(matches(isDisplayed()))
            }
        }
    }

    @Test(timeout = 120_000)
    fun fileImportShowsConfirmationFirstAndInstallsOnlyAfterUserApproval() {
        val packFile = File(directory, "uitest-hello-$id.js").apply {
            writeText(singleFilePack)
        }
        val monitor = filePickMonitor(packFile)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<PackManageActivity>(
                Intent(context, PackManageActivity::class.java)
            ).use { scenario ->
                awaitPacksLoaded(scenario)
                openActionBarOverflowOrOptionsMenu(context)
                onView(withText(R.string.pack_import_local))
                    .inRoot(isPlatformPopup())
                    .perform(click())
                awaitImportDialog(scenario)
                // 确认对话框展示 manifest 摘要, 未确认前不注册
                onView(withText(R.string.pack_import_confirm))
                    .inRoot(isDialog())
                    .check(matches(isDisplayed()))
                onView(withId(R.id.tv_summary))
                    .inRoot(isDialog())
                    .check(matches(withText(containsString("UI Test Hello"))))
                assertEquals(0, PackHost.manager.list().count { it.manifest.id.value == "uitest.hello" })
                // 旋转后确认对话框仍在, 勾选状态不丢失
                scenario.recreate()
                awaitImportDialog(scenario)
                onView(withId(R.id.tv_ok)).inRoot(isDialog()).perform(click())
                await {
                    PackHost.manager.list().any { it.manifest.id.value == "uitest.hello" }
                }
                awaitPacksLoaded(scenario)
                onView(withText(containsString("UI Test Hello")))
                    .check(matches(isDisplayed()))
                screenshot("pack-import-confirm-installed")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test(timeout = 120_000)
    fun writeToolsStayUncheckedByDefaultAndOpenNetworkBlocksInstallWithoutConsent() {
        val packJson = toolsPackJson(
            versionCode = 1,
            version = "1.0.0",
            network = """{"mode":"open","reason":"dynamic host"}""",
            tools = """[
                {"name":"uitest_add","command":"hello"},
                {"name":"uitest_write","command":"hello","write":true}
            ]""",
        )
        val packFile = File(directory, "uitest-open-$id.zip")
        writeZip(packFile, packJson)
        val monitor = filePickMonitor(packFile)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<PackManageActivity>(
                Intent(context, PackManageActivity::class.java)
            ).use { scenario ->
                awaitPacksLoaded(scenario)
                openActionBarOverflowOrOptionsMenu(context)
                onView(withText(R.string.pack_import_local))
                    .inRoot(isPlatformPopup())
                    .perform(click())
                awaitImportDialog(scenario)
                // 写工具默认不勾选, 只读工具默认勾选
                onView(
                    allOf(withId(R.id.cb_grant), withText(containsString("uitest_write")))
                ).inRoot(isDialog()).check(matches(not(isChecked())))
                onView(
                    allOf(withId(R.id.cb_grant), withText(containsString("uitest_add")))
                ).inRoot(isDialog()).check(matches(isChecked()))
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[PackManageViewModel::class.java]
                    val selection = model.importSelection
                    assertNotNull(selection)
                    assertEquals(false, selection!!.toolSelection["uitest_write"])
                    assertEquals(true, selection.toolSelection["uitest_add"])
                }
                // network=open 未勾选同意时无法安装
                onView(withId(R.id.cb_network_open)).inRoot(isDialog()).check(matches(not(isChecked())))
                onView(withId(R.id.tv_ok)).inRoot(isDialog()).perform(click())
                onView(withText(R.string.pack_network_open_required))
                    .inRoot(isDialog())
                    .check(matches(isDisplayed()))
                assertEquals(0, PackHost.manager.list().count { it.manifest.id.value == "uitest.tools" })
                screenshot("pack-open-network-blocked")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test(timeout = 120_000)
    fun corruptPackFileSurfacesARedCardMessageWithoutCrashing() {
        val packFile = File(directory, "uitest-bad-$id.zip")
        writeZip(packFile, "{\"schemaVersion\":9,\"id\":\"uitest.bad\"")
        val monitor = filePickMonitor(packFile)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<PackManageActivity>(
                Intent(context, PackManageActivity::class.java)
            ).use { scenario ->
                awaitPacksLoaded(scenario)
                openActionBarOverflowOrOptionsMenu(context)
                onView(withText(R.string.pack_import_local))
                    .inRoot(isPlatformPopup())
                    .perform(click())
                // 红牌错误必须可见: messageEvent 携带诊断文本, 且不弹出确认对话框
                await {
                    var message: String? = null
                    scenario.onActivity { activity ->
                        val model = ViewModelProvider(activity)[PackManageViewModel::class.java]
                        message = model.messageEvent.value?.peek()
                    }
                    message?.contains("MANIFEST") == true
                }
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[PackManageViewModel::class.java]
                    assertEquals(null, model.importUiStateLiveData.value)
                }
                assertEquals(0, PackHost.manager.list().count { it.manifest.id.value == "uitest.bad" })
                screenshot("pack-import-red-card")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test(timeout = 180_000)
    fun writeToolGrantStaysOffByDefaultAndIsEnabledOnlyThroughTheManagementDialog() {
        val packFile = File(directory, "uitest-grant-$id.zip")
        writeZip(
            packFile,
            toolsPackJson(
                versionCode = 1,
                version = "1.0.0",
                network = null,
                tools = """[
                    {"name":"uitest_add","command":"hello"},
                    {"name":"uitest_write","command":"hello","write":true}
                ]""",
            ),
        )
        val packId = io.legado.pack.api.PackId.require("uitest.tools")
        val monitor = filePickMonitor(packFile)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<PackManageActivity>(
                Intent(context, PackManageActivity::class.java)
            ).use { scenario ->
                awaitPacksLoaded(scenario)
                openActionBarOverflowOrOptionsMenu(context)
                onView(withText(R.string.pack_import_local))
                    .inRoot(isPlatformPopup())
                    .perform(click())
                awaitImportDialog(scenario)
                // 安装时写工具未勾选: 宿主写授权保持关闭, 不存在自动授予
                onView(
                    allOf(withId(R.id.cb_grant), withText(containsString("uitest_write")))
                ).inRoot(isDialog()).check(matches(not(isChecked())))
                onView(withId(R.id.tv_ok)).inRoot(isDialog()).perform(click())
                await { PackHost.manager.info(packId) != null }
                assertFalse(PackHost.isPackWriteToolEnabled(packId, "uitest_write"))
                // 管理对话框显式开启/关闭
                awaitPacksLoaded(scenario)
                onView(withId(R.id.iv_menu_more)).perform(click())
                onView(withText(R.string.pack_write_tools_menu))
                    .inRoot(isPlatformPopup())
                    .perform(click())
                awaitWriteToolsDialog(scenario)
                onView(
                    allOf(withId(R.id.cb_grant), withText(containsString("uitest_write")))
                ).inRoot(isDialog()).check(matches(not(isChecked())))
                onView(
                    allOf(withId(R.id.cb_grant), withText(containsString("uitest_write")))
                ).inRoot(isDialog()).perform(click())
                await { PackHost.isPackWriteToolEnabled(packId, "uitest_write") }
                onView(
                    allOf(withId(R.id.cb_grant), withText(containsString("uitest_write")))
                ).inRoot(isDialog()).perform(click())
                await { !PackHost.isPackWriteToolEnabled(packId, "uitest_write") }
                screenshot("pack-write-tool-grants")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test(timeout = 180_000)
    fun upgradingWithChangedPermissionsHighlightsTheDiffAndAsksForConfirmationAgain() {
        val first = File(directory, "uitest-tools-v1-$id.zip")
        writeZip(
            first,
            toolsPackJson(
                versionCode = 1,
                version = "1.0.0",
                network = null,
                tools = """[{"name":"uitest_add","command":"hello"}]""",
            ),
        )
        val second = File(directory, "uitest-tools-v2-$id.zip")
        writeZip(
            second,
            toolsPackJson(
                versionCode = 2,
                version = "1.1.0",
                network = """{"mode":"open","reason":"dynamic host"}""",
                tools = """[
                    {"name":"uitest_add","command":"hello"},
                    {"name":"uitest_write","command":"hello","write":true}
                ]""",
            ),
        )
        var current: File = first
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.component?.className == HandleFileActivity::class.java.name) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(providerUri(current)))
                } else {
                    null
                }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<PackManageActivity>(
                Intent(context, PackManageActivity::class.java)
            ).use { scenario ->
                awaitPacksLoaded(scenario)
                // 第一次安装: 无权限差异提示
                openActionBarOverflowOrOptionsMenu(context)
                onView(withText(R.string.pack_import_local))
                    .inRoot(isPlatformPopup())
                    .perform(click())
                awaitImportDialog(scenario)
                onView(withId(R.id.tv_permission_diff)).inRoot(isDialog())
                    .check(matches(not(isDisplayed())))
                onView(withId(R.id.tv_ok)).inRoot(isDialog()).perform(click())
                await {
                    PackHost.manager.list().any { it.manifest.id.value == "uitest.tools" }
                }
                awaitPacksLoaded(scenario)
                // 第二次导入: 权限变化必须高亮并重新确认
                current = second
                openActionBarOverflowOrOptionsMenu(context)
                onView(withText(R.string.pack_import_local))
                    .inRoot(isPlatformPopup())
                    .perform(click())
                awaitImportDialog(scenario)
                onView(withId(R.id.tv_permission_diff)).inRoot(isDialog())
                    .check(matches(isDisplayed()))
                onView(withId(R.id.tv_permission_diff)).inRoot(isDialog())
                    .check(matches(withText(containsString("uitest_write"))))
                onView(withId(R.id.tv_upgrade_line)).inRoot(isDialog())
                    .check(matches(withText(containsString("1.1.0"))))
                // 仍未重新确认前不落库新版本
                assertEquals(
                    1L,
                    PackHost.manager.info(io.legado.pack.api.PackId.require("uitest.tools"))
                        ?.manifest?.versionCode
                )
                screenshot("pack-upgrade-permission-diff")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun filePickMonitor(file: File): Instrumentation.ActivityMonitor =
        object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.component?.className == HandleFileActivity::class.java.name) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(providerUri(file)))
                } else {
                    null
                }
        }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)

    private fun awaitPacksLoaded(scenario: ActivityScenario<PackManageActivity>) {
        await {
            var loaded = false
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[PackManageViewModel::class.java]
                loaded = model.packsLiveData.value != null
            }
            loaded
        }
    }

    private fun awaitImportDialog(scenario: ActivityScenario<PackManageActivity>) {
        await {
            var ready = false
            scenario.onActivity { activity ->
                val fragment =
                    activity.supportFragmentManager.findFragmentByTag("packImportConfirm")
                ready = (fragment as? PackImportConfirmDialog)?.dialog?.isShowing == true &&
                    fragment?.view != null
            }
            ready
        }
    }

    private fun awaitWriteToolsDialog(scenario: ActivityScenario<PackManageActivity>) {
        await {
            var ready = false
            scenario.onActivity { activity ->
                val fragment =
                    activity.supportFragmentManager.findFragmentByTag(PackWriteToolsDialog.TAG)
                ready = (fragment as? PackWriteToolsDialog)?.dialog?.isShowing == true &&
                    fragment?.view != null
            }
            ready
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("扩展包管理界面未达到预期状态", condition())
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "ui-regression/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
}
