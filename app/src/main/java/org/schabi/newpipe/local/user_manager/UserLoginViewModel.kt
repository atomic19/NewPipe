package org.schabi.newpipe.local.user_manager

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class UserLoginViewModel(application: Application) : AndroidViewModel(application) {

    override fun onCleared() {
        super.onCleared()
    }

    sealed class UserLoginState
}
