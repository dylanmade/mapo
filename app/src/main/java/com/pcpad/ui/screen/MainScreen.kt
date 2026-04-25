package com.pcpad.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pcpad.ui.viewmodel.MainViewModel

private val PLACEHOLDER_KEYS = listOf(
    "W", "A", "S", "D",
    "Q", "E", "R", "F",
    "Shift", "Ctrl", "Alt", "Tab",
    "1", "2", "3", "Esc"
)

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val layouts by viewModel.layouts.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "pcpad" + if (layouts.isNotEmpty()) " — ${layouts.size} layout(s)" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(PLACEHOLDER_KEYS) { key ->
                    Button(
                        onClick = { viewModel.onKeyPress(key) },
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text(text = key, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
