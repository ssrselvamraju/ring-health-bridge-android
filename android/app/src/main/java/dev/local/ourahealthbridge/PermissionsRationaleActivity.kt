package dev.local.ourahealthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.ourahealthbridge.ui.BridgeTheme

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Health data privacy", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Ring Health Bridge reads your ring directly over Bluetooth and writes " +
                                "only the health record types you approve to Health Connect.",
                        )
                        Text(
                            "It has no internet permission, account, advertising, analytics, or " +
                                "cloud service. Ring data remains on this phone unless you choose " +
                                "to share it through Health Connect.",
                        )
                        Text(
                            "The optional historical audits read only records attributed to the " +
                                "official Oura or Samsung Health app. They report schema counts and " +
                                "timing structure on this phone; they do not export records or " +
                                "display raw values.",
                        )
                        Text("You can revoke Health Connect access or delete local data at any time.")
                    }
                }
            }
        }
    }
}
