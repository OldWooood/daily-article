package com.deatrg.dailyarticle

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deatrg.dailyarticle.data.Article
import com.deatrg.dailyarticle.data.ArticleRepository
import com.deatrg.dailyarticle.di.AppModule
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Loading : UiState
    data class Done(val article: Article, val isRandom: Boolean) : UiState
    data class Error(val msg: String, val isRandom: Boolean) : UiState
}

/** 阅读位置：LazyColumn 的首个可见条目下标 + 像素偏移。 */
data class Scroll(val index: Int, val offset: Int)

class ArticleViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: ArticleRepository = AppModule.provideRepository(app)

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _scroll = MutableStateFlow(Scroll(0, 0))
    val scroll: StateFlow<Scroll> = _scroll.asStateFlow()

    /** 正在换随机文章：老文章继续显示，顶部只露一条细进度条，避免整屏闪成菊花。 */
    private val _randomLoading = MutableStateFlow(false)
    val randomLoading: StateFlow<Boolean> = _randomLoading.asStateFlow()

    /** 当前正在进行的网络任务，用于在换文时取消上一次请求，避免慢响应覆盖新文章。 */
    private var fetchJob: Job? = null

    /**
     * 用户按了返回键：不再缓存这次的阅读进度。
     * 文章本身保留（下次进来还是同一篇），但位置清零回到顶部。
     * 后续的滚动上报会被忽略，避免退出瞬间落盘把旧位置写回去。
     */
    @Volatile
    private var backPressed = false

    init {
        restore()
    }

    fun loadDaily() = load(daily = true)

    fun loadRandom() = load(daily = false)

    /**
     * 冷启动：优先回到用户上次在读的位置，而不是把用户弹回当天的「每日一篇」。
     * 这样点过「随机一篇」之后退出再进来，看到的还是那一篇。
     */
    private fun restore() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val restored = repository.loadLastRead(ctx)
            if (restored == null) {
                load(daily = true)
                return@launch
            }
            show(restored.article, restored.isRandom)
            _scroll.value = Scroll(restored.scrollIndex, restored.scrollOffset)
            // 只有「每日一篇」且已经跨天了才需要刷新；
            // 随机文章永远保持原样，否则会丢失用户正在读的内容。
            if (!restored.isRandom && restored.date != LocalDate.now().toString()) {
                load(daily = true, showLoading = false)
            }
        }
    }

    private fun load(daily: Boolean, showLoading: Boolean = true) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            val ctx = getApplication<Application>()

            if (daily) {
                // 当天缓存命中直接显示，不请求网络
                val cached = repository.loadDailyToday(ctx)
                if (cached != null) {
                    show(cached, isRandom = false)
                    return@launch
                }
            }
            // 随机换文且已有文章在读：保留老文章，只亮顶部进度条
            val keepVisible = !daily && _state.value is UiState.Done
            if (keepVisible) _randomLoading.value = true
            else if (showLoading) _state.value = UiState.Loading

            try {
                val a = if (daily) repository.getDaily(ctx) else repository.getRandom(ctx)
                show(a, isRandom = !daily)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 用户又点了一次，属正常打断
            } catch (e: Exception) {
                Log.w(TAG, "load(daily=$daily) failed", e)
                // 兜底顺序：任意一天的每日文章 → 上次在读的文章 → 报错
                val fallback = repository.loadFallback(ctx)
                if (fallback != null) show(fallback, isRandom = false)
                else _state.value = UiState.Error("加载失败：${e.message ?: "网络错误"}", isRandom = !daily)
            } finally {
                _randomLoading.value = false
            }
        }
    }

    /** 显示一篇文章，并把阅读位置重置到顶部。 */
    private fun show(a: Article, isRandom: Boolean) {
        backPressed = false
        _scroll.value = Scroll(0, 0)
        _state.value = UiState.Done(a, isRandom)
    }

    /** 滚动时记录位置（UI 层已做防抖）。 */
    fun onScrolled(index: Int, offset: Int) {
        if (backPressed) return
        val next = Scroll(index, offset)
        if (next == _scroll.value) return
        _scroll.value = next
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            repository.saveScroll(ctx, index, offset)
        }
    }

    /** 返回键退出：清掉阅读进度，下次从顶部开始读。 */
    fun onBackPressed() {
        backPressed = true
        _scroll.value = Scroll(0, 0)
        // 同步清盘，见 ArticleStore.clearScroll 注释
        repository.clearScroll(getApplication())
    }

    private companion object { const val TAG = "DailyArticle" }
}
