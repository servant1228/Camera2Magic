package com.nothing.camera2magic.viewmodel

import androidx.lifecycle.ViewModel
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.utils.LogEntry
import kotlinx.coroutines.flow.StateFlow

class LogViewModel : ViewModel() {
    val logs: StateFlow<List<LogEntry>> = Dog.logs
    fun clear() = Dog.clear()
}
