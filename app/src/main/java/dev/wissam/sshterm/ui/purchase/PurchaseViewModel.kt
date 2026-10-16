package dev.wissam.sshterm.ui.purchase

import android.app.Activity
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wissam.sshterm.core.billing.PlayBillingRepository
import javax.inject.Inject

@HiltViewModel
class PurchaseViewModel @Inject constructor(private val billing: PlayBillingRepository) : ViewModel() {
    val ownerDevelopmentPro = billing.ownerDevelopmentPro
    val enforcesFreeLimits = billing.enforcesFreeLimits
    val freeLimits = billing.freeLimits
    val state = billing.state
    fun restore() = billing.refresh()
    fun purchase(activity: Activity) = billing.purchase(activity)
}
