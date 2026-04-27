package com.pcpad.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pcpad.data.model.Profile

@Composable
fun ProfileDrawerContent(
    activeProfile: Profile?,
    onChangeProfile: () -> Unit
) {
    ModalDrawerSheet {
        Text(
            text = activeProfile?.name ?: "Loading...",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleLarge
        )
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text("Change Profile") },
            selected = false,
            onClick = onChangeProfile
        )
    }
}
