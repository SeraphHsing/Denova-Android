package com.denova.novara

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.denova.novara.core.db.NovaraDatabase
import com.denova.novara.feature.agent.AgentViewModel
import com.denova.novara.feature.connection.ConnectionViewModel
import com.denova.novara.feature.debug.DebugPanelViewModel
import com.denova.novara.feature.editor.EditorViewModel
import com.denova.novara.core.persistence.PreferencesStore
import com.denova.novara.feature.bookshelf.BookShelfViewModel
import com.denova.novara.feature.game.GameViewModel
import com.denova.novara.feature.project.ProjectDirectoryViewModel
import com.denova.novara.feature.settings.AppCompatLocaleApplier
import com.denova.novara.feature.settings.SettingsViewModel
import com.denova.novara.feature.terminal.TerminalViewModel

/**
 * P2 组合根（手动注入，单 app 模块）。
 *
 * 集中构造应用级共享依赖与各 ViewModel 工厂，替代原先在 `MainActivity` 里逐个 `viewModel { }` 的散落方式。
 * 依赖方向：本类在 app 层（组合根），依赖各 feature 的 ViewModel 是允许的；core/ 不反向依赖 feature。
 *
 * 现在只提供 [viewModelFactory]；后续 Room 草稿库、请求退休机制、环境配置等共享依赖统一在此创建。
 */
class AppContainer(private val application: Application) {

    /** 本地结构化存储（草稿与命令身份）；懒加载单例，业务只经 DraftStorage / CommandStorage 接口访问。 */
    val database: NovaraDatabase by lazy {
        Room.databaseBuilder(application, NovaraDatabase::class.java, "novara.db").build()
    }

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { ConnectionViewModel(application) }
        initializer { EditorViewModel(application) }
        initializer { GameViewModel(application) }
        initializer { AgentViewModel(application) }
        initializer { TerminalViewModel(application) }
        initializer { ProjectDirectoryViewModel(application) }
        initializer { BookShelfViewModel(application) }
        initializer { DebugPanelViewModel(application) }
        initializer { SettingsViewModel(PreferencesStore(application), AppCompatLocaleApplier) }
    }
}
