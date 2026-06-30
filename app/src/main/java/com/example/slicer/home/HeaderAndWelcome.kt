package com.example.slicer.home

import com.example.slicer.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
internal fun AppHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Slicer logo",
                modifier = Modifier.size(50.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Slicer",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Simple and local-first image compression.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag("slicer-welcome-screen"),
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Slicer logo",
                modifier = Modifier.size(84.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Welcome to Slicer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Reduce image sizes quickly and privately on device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Pick a few photos, adjust quality, and save optimized outputs to a folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text("Get started", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
