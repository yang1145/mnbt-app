package com.mnbt.app.ui.editor

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.dsl.languages
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * sora-editor + TextMate 的一次性装配（PRD §10.1 懒初始化：不在 Application.onCreate 触发，
 * 只有真正进编辑器才加载 grammar/theme）。
 *
 * 关键事实（按 sora-editor tag 0.22.2 源码与构件字节码核实，editor 与 language-textmate 必须同版本互配；
 * 凭记忆写会踩坑的点特别标注）：
 * - 类名是 `TextMateLanguage`（大写 M），0.22.x 里 **没有** FileTextmateLanguage（早已删除）。
 * - grammar 注册走 `GrammarRegistry.getInstance().loadGrammars(languages { ... })` DSL；
 *   DSL 内部通过 `FileProviderRegistry` 拿流，所以必须先挂 [AssetsFileResolver]。
 * - `doLoadGrammar` 会校验文件里的 scopeName 与声明一致，不一致直接抛 IllegalStateException。
 * - 主题注册名 = 文件名去扩展名（ThemeModel 单参构造取 filePath），双参构造可显式命名，这里显式命名。
 * - `TextMateColorScheme.create(ThemeRegistry)` 必须在主题已 setTheme 之后调用才有意义；
 *   主题切换通过 ThemeRegistry 的监听器传播，但 editor 侧需重建/重设 colorScheme 才会重绘配色。
 * - 首次加载 PHP grammar（约 138KB、53 个 repository）要做 oniguruma（0.22.x 由 joni 实现）正则编译，
 *   放 IO 线程，主线程调用会肉眼可见地卡。
 */
object TextmateSupport {

    const val PHP_SCOPE = "source.php"

    private const val GRAMMAR_PATH = "textmate/php.tmLanguage.json"
    private const val THEME_LIGHT = "mnbt-light"
    private const val THEME_DARK = "mnbt-dark"
    private const val THEME_LIGHT_PATH = "textmate/mnbt-light.json"
    private const val THEME_DARK_PATH = "textmate/mnbt-dark.json"

    private val mutex = Mutex()

    /** true 表示 grammar 与两份 theme 已就绪，可安全调用 [createLanguage]。 */
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()

    /** 初始化失败原因（文件缺失/theme 解析失败等）。失败不阻塞打开：UI 退化为纯文本编辑。 */
    private val _failure = MutableStateFlow<String?>(null)
    val failure = _failure.asStateFlow()

    /** 幂等：并发/重复调用只会真正加载一次。必须从主线程协程调，内部自行切 IO。 */
    suspend fun ensureInitialized(appContext: Context) {
        if (_ready.value || _failure.value != null) return
        mutex.withLock {
            if (_ready.value || _failure.value != null) return
            val result = runCatching {
                withContext(Dispatchers.IO) { doInit(appContext) }
            }
            result.fold(
                onSuccess = { _ready.value = true },
                onFailure = { _failure.value = it.message ?: it.javaClass.simpleName },
            )
        }
    }

    private fun doInit(appContext: Context) {
        val providers = FileProviderRegistry.getInstance()
        providers.addFileProvider(AssetsFileResolver(appContext.assets))

        GrammarRegistry.getInstance().loadGrammars(
            languages {
                language("php") {
                    grammar = GRAMMAR_PATH
                    scopeName = PHP_SCOPE
                }
            }
        )

        val themeRegistry = ThemeRegistry.getInstance()
        themeRegistry.loadTheme(
            ThemeModel(themeSourceOf(providers, THEME_LIGHT_PATH), THEME_LIGHT)
        )
        val darkModel = ThemeModel(themeSourceOf(providers, THEME_DARK_PATH), THEME_DARK)
        darkModel.setDark(true)
        themeRegistry.loadTheme(darkModel, false)
    }

    private fun themeSourceOf(providers: FileProviderRegistry, path: String): IThemeSource =
        IThemeSource.fromInputStream(providers.tryGetInputStream(path), path, null)

    /** 按明暗切主题；只切注册表，editor 的 colorScheme 由调用方重建后重设。 */
    fun applyTheme(dark: Boolean): Boolean =
        ThemeRegistry.getInstance().setTheme(if (dark) THEME_DARK else THEME_LIGHT)

    /**
     * 创建一个绑定 source.php 的语言实例。每个编辑器视图各建一个，
     * 视图销毁时必须 [TextMateLanguage.destroy]（grammar 本体在 Registry 里共享，不受影响）。
     * 当前版本不需要补全弹栏，autoCompleteEnabled 关闭以省一份 identifier 索引。
     */
    fun createLanguage(): TextMateLanguage =
        TextMateLanguage.create(PHP_SCOPE, false)

    /** 以 ThemeRegistry 当前主题构造配色。ThemeModel 未加载时内部会 load，放 IO 更稳。 */
    fun createColorScheme(): TextMateColorScheme =
        TextMateColorScheme.create(ThemeRegistry.getInstance())
}
