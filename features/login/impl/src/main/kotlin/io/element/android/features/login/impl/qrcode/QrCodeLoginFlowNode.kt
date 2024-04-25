/*
 * Copyright (c) 2024 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.element.android.features.login.impl.qrcode

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.operation.replace
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.element.android.anvilannotations.ContributesNode
import io.element.android.features.login.impl.screens.qrcode.confirmation.QrCodeConfirmationNode
import io.element.android.features.login.impl.screens.qrcode.confirmation.QrCodeConfirmationStep
import io.element.android.features.login.impl.screens.qrcode.error.QrCodeErrorNode
import io.element.android.features.login.impl.screens.qrcode.intro.QrCodeIntroNode
import io.element.android.features.login.impl.screens.qrcode.scan.QrCodeScanNode
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.di.AppScope
import io.element.android.libraries.matrix.api.auth.qrlogin.MatrixQrCodeLoginData
import io.element.android.libraries.matrix.api.auth.qrlogin.QrCodeLoginStep
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

@ContributesNode(AppScope::class)
class QrCodeLoginFlowNode @AssistedInject constructor(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val qrCodeLoginPresenter: QrCodeLoginPresenter,
) : BaseFlowNode<QrCodeLoginFlowNode.NavTarget>(
    backstack = BackStack(
        initialElement = NavTarget.Initial,
        savedStateMap = buildContext.savedStateMap,
    ),
    buildContext = buildContext,
    plugins = plugins,
) {
    override fun onBuilt() {
        super.onBuilt()

        lifecycleScope.launch {
            qrCodeLoginPresenter.currentLoginStep
                .collect { step ->
                    when (step) {
                        is QrCodeLoginStep.EstablishingSecureChannel -> {
                            backstack.replace(NavTarget.QrCodeConfirmation(QrCodeConfirmationStep.DisplayCheckCode(step.checkCode)))
                        }
                        is QrCodeLoginStep.WaitingForToken -> {
                            backstack.replace(NavTarget.QrCodeConfirmation(QrCodeConfirmationStep.DisplayVerificationCode(step.userCode)))
                        }
                        else -> Unit
                    }
                }
        }
    }

    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Initial : NavTarget

        @Parcelize
        data object QrCodeScan : NavTarget

        @Parcelize
        data class QrCodeConfirmation(val step: QrCodeConfirmationStep) : NavTarget

        @Parcelize
        // TODO specify the error type
        data class Error(val message: String) : NavTarget
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node {
        return when (navTarget) {
            is NavTarget.Initial -> {
                val callback = object : QrCodeIntroNode.Callback {
                    override fun onCancelClicked() {
                        navigateUp()
                    }

                    override fun onContinue() {
                        backstack.push(NavTarget.QrCodeScan)
                    }
                }
                createNode<QrCodeIntroNode>(buildContext, plugins = listOf(callback))
            }
            is NavTarget.QrCodeScan -> {
                val callback = object : QrCodeScanNode.Callback {
                    override fun onScannedCode(qrCodeLoginData: MatrixQrCodeLoginData) {
                        lifecycleScope.launch {
                            startAuthentication(qrCodeLoginData)
                        }
                    }

                    override fun onCancelClicked() {
                        backstack.pop()
                    }
                }
                createNode<QrCodeScanNode>(buildContext, plugins = listOf(callback))
            }
            is NavTarget.QrCodeConfirmation -> {
                val callback = object : QrCodeConfirmationNode.Callback {
                    override fun onCancel() {
                        // TODO actually cancel the login attempt
                        navigateUp()
                    }
                }
                createNode<QrCodeConfirmationNode>(buildContext, plugins = listOf(navTarget.step, callback))
            }
            is NavTarget.Error -> {
                // TODO specify the error type
                createNode<QrCodeErrorNode>(buildContext)
            }
        }
    }

    private suspend fun startAuthentication(qrCodeLoginData: MatrixQrCodeLoginData) {
        qrCodeLoginPresenter.authenticate(qrCodeLoginData)
            .onSuccess {
                println("Logged into session $it")
            }
            .onFailure {
                // TODO specify the error type
                backstack.push(NavTarget.Error(it.message ?: "Unknown error"))
            }
    }

    @Composable
    override fun View(modifier: Modifier) {
        BackstackView()
    }
}