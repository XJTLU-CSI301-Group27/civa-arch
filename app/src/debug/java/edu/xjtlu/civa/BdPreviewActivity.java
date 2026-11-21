package edu.xjtlu.civa;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import edu.xjtlu.civa.core.ui.CivaWindow;
import edu.xjtlu.civa.feature.bd.BdHomeFragment;

/**
 * Debug-only visual entry point for the home screen. It neither creates a login nor
 * calls protected APIs, and it never replaces MainActivity as the launcher.
 */
public final class BdPreviewActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_activity_main);
        CivaWindow.apply(this);
        BottomNavigationView navigation = findViewById(R.id.app_navigation);
        // The preview implements Home only; the other destinations stay visible but inert.
        navigation.getMenu().findItem(R.id.app_recipes).setEnabled(false);
        navigation.getMenu().findItem(R.id.app_inventory).setEnabled(false);
        navigation.getMenu().findItem(R.id.app_diary).setEnabled(false);
        navigation.getMenu().findItem(R.id.app_profile).setEnabled(false);
        navigation.setOnItemSelectedListener(item -> item.getItemId() == R.id.app_today);
        if (savedInstanceState == null) getSupportFragmentManager().beginTransaction()
                .replace(R.id.app_content, new BdHomeFragment()).commit();
    }
}
