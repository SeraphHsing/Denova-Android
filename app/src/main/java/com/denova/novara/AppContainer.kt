package com.denova.novara

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denova.novara.feature.agent.AgentViewModel
import com.denova.novara.feature.connection.ConnectionViewModel
import com.denova.novara.feature.debug.DebugPanelViewModel
import com.denova.novara.feature.editor.EditorViewModel
import com.denova.novara.feature.game.GameViewModel
import com.denova.novara.feature.terminal.TerminalViewModel

/**
 * P2 组合根（手动注入，单 app 模块）。
 *
 * 集中构造应用级共享依赖与各 ViewModel 工厂，替代原先在 `MainActivity` 里逐个 `viewModel { }` 的散落方式。
 * 依赖方向：本类在 app 层（组合根），依赖各 feature 的 ViewModel 是允许的；core/ 不反向依赖 feature。
 *
 * 现在只提供 [viewModelFactory]；后续 Room 草稿库、请求退休机制、环境配置等共享依赖统一在此创建。
 */
class AppContainer(application: Application) {

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { ConnectionViewModel(application) }
        initializer { EditorViewModel(application) }
        initializer { GameViewModel(application) }
        initializer { AgentViewModel(application) }
        initializer { TerminalViewModel(application) }
        initializer { DebugPanelViewModel(application) }
    }
}
