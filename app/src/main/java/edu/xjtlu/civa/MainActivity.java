package edu.xjtlu.civa;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import edu.xjtlu.civa.core.ui.CivaWindow;
import edu.xjtlu.civa.core.ui.Route;
import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.data.ApiProvider;
import edu.xjtlu.civa.core.data.AuthRepository;
import edu.xjtlu.civa.core.data.SessionStore;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.catalog.RecipeCatalogFragment;
import edu.xjtlu.civa.feature.ac.diary.DiaryFragment;
import edu.xjtlu.civa.feature.ac.inventory.InventoryFragment;
import edu.xjtlu.civa.feature.ac.inventory.InventoryHost;
import edu.xjtlu.civa.feature.bd.BdHost;
import edu.xjtlu.civa.feature.ef.HouseholdHost;
import edu.xjtlu.civa.feature.ef.ProfileHost;
import edu.xjtlu.civa.feature.ef.LoginFragment;
import edu.xjtlu.civa.feature.ef.LoginHost;

public class MainActivity extends AppCompatActivity implements LoginHost, HouseholdHost, BdHost, InventoryHost, AcHost, ProfileHost {
    private AppContainer container;
    private AppNavigator navigator;
    private NavigationBarView bottomNavigation;
    private NavigationRailView railNavigation;
    private boolean railMode;
    private int checkedTab = R.id.app_today;
    private boolean introShowing;
    private long introStarted;
    private AppIntroView intro;

    @Override protected void onCreate(Bundle savedInstanceState) {
        container = ((CivaApplication) getApplication()).container();
        boolean restore = container.sessions.isLoggedIn() || getLastCustomNonConfigurationInstance() != null;
        super.onCreate(restore ? savedInstanceState : null);
        setContentView(R.layout.app_activity_main);
        introShowing = savedInstanceState == null || !savedInstanceState.getBoolean("app.intro.finished", false);
        boolean introSeen = getSharedPreferences("civa_intro", MODE_PRIVATE).getBoolean("seen", false);
        if (!edu.xjtlu.civa.core.ui.Motion.enabled() || introSeen) introShowing = false;
        introStarted = savedInstanceState == null ? android.os.SystemClock.elapsedRealtime() : savedInstanceState.getLong("app.intro.started", android.os.SystemClock.elapsedRealtime());
        if (android.os.SystemClock.elapsedRealtime() - introStarted >= AppIntroView.DURATION) introShowing = false;
        findViewById(android.R.id.content).setTag(edu.xjtlu.civa.core.ui.R.id.civa_intro_blocked, introShowing);
        CivaWindow.apply(this);
        navigator = new AppNavigator(getSupportFragmentManager(), container.sessions);
        setupNavigation();
        if (savedInstanceState != null) checkedTab = savedInstanceState.getInt("app.tab", R.id.app_today);
        if (savedInstanceState == null || !restore) navigator.navigate(container.sessions.isLoggedIn() ? Route.TODAY : Route.LOGIN, null);
        else if (!container.sessions.isLoggedIn() && !(getSupportFragmentManager().findFragmentById(R.id.app_content) instanceof LoginFragment)) {
            navigator.navigate(Route.LOGIN, null);
        }
        container.sessionExpired.observe(this, ignored -> showLoginIfNeeded());
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            @Override public void onFragmentViewCreated(androidx.fragment.app.FragmentManager fm, androidx.fragment.app.Fragment fragment, View view, Bundle state) { updateChrome(fragment); }
        }, false);
        updateChrome(getSupportFragmentManager().findFragmentById(R.id.app_content));
        if (introShowing) {
            intro = new AppIntroView(this, introStarted, savedInstanceState != null && savedInstanceState.getBoolean("app.intro.voiced", false), this::finishIntro);
            intro.setClickable(true);
            intro.setOnClickListener(ignored -> finishIntro());
            ((android.widget.FrameLayout)findViewById(R.id.app_root)).addView(intro, new android.widget.FrameLayout.LayoutParams(-1,-1));
        }
    }

    private void finishIntro() {
        if (!introShowing) return;
        introShowing = false;
        getSharedPreferences("civa_intro", MODE_PRIVATE).edit().putBoolean("seen", true).apply();
        if (intro != null) {
            final android.view.View fading = intro;
            final android.view.ViewGroup parent = (android.view.ViewGroup) fading.getParent();
            intro = null;
            if (parent != null) {
                if (edu.xjtlu.civa.core.ui.Motion.enabled()) {
                    fading.animate().alpha(0f).setDuration(180L).withEndAction(() -> parent.removeView(fading)).start();
                } else {
                    parent.removeView(fading);
                }
            }
        }
        View content = findViewById(android.R.id.content);
        content.setTag(edu.xjtlu.civa.core.ui.R.id.civa_intro_blocked, false);
        playWelcome(content); updateChrome(getSupportFragmentManager().findFragmentById(R.id.app_content));
    }
    private void playWelcome(View view) {
        if (view instanceof edu.xjtlu.civa.core.ui.BakeryWelcomeLayout welcome) welcome.playEntrance();
        if (view instanceof android.view.ViewGroup group) for (int i=0;i<group.getChildCount();i++) playWelcome(group.getChildAt(i));
    }

    /** Which bottom/rail item owns the current primary page; sub-pages keep the previous one. */
    private int tabFor(androidx.fragment.app.Fragment fragment) {
        if (fragment instanceof edu.xjtlu.civa.feature.bd.BdHomeFragment) return R.id.app_today;
        if (fragment instanceof RecipeCatalogFragment) return R.id.app_recipes;
        if (fragment instanceof InventoryFragment) return R.id.app_inventory;
        if (fragment instanceof DiaryFragment) return R.id.app_diary;
        if (fragment instanceof edu.xjtlu.civa.feature.ef.ProfileFragment) return R.id.app_profile;
        return 0;
    }

    private void setupNavigation() {
        bottomNavigation = findViewById(R.id.app_navigation);
        railNavigation = findViewById(R.id.app_navigation_rail);
        NavigationBarView.OnItemSelectedListener listener = item -> {
            int id = item.getItemId();
            checkedTab = id;
            if (id == R.id.app_today) navigator.navigate(Route.TODAY, null);
            else if (id == R.id.app_recipes) navigator.navigate(Route.RECIPES, null);
            else if (id == R.id.app_inventory) navigator.navigate(Route.INVENTORY, null);
            else if (id == R.id.app_diary) navigator.navigate(Route.DIARY, null);
            else if (id == R.id.app_profile) navigator.navigate(Route.PROFILE, null);
            else return false;
            return true;
        };
        bottomNavigation.setOnItemSelectedListener(listener);
        railNavigation.setOnItemSelectedListener(listener);
        railMode = NavigationMode.forWidth(getResources().getConfiguration().screenWidthDp) == NavigationMode.Kind.RAIL;
        setNavigationVisible(container.sessions.isLoggedIn());
    }

    /** Bottom bar on narrow windows, left rail on wide ones (landscape phone or tablet). */
    private void setNavigationVisible(boolean visible) {
        bottomNavigation.setVisibility(!visible || railMode ? View.GONE : View.VISIBLE);
        railNavigation.setVisibility(visible && railMode ? View.VISIBLE : View.GONE);
    }

    private void checkNavigationItem(int menuId) {
        android.view.MenuItem bottom = bottomNavigation.getMenu().findItem(menuId);
        if (bottom != null) bottom.setChecked(true);
        android.view.MenuItem rail = railNavigation.getMenu().findItem(menuId);
        if (rail != null) rail.setChecked(true);
    }

    private void updateChrome(androidx.fragment.app.Fragment fragment) {
        boolean auth = fragment instanceof LoginFragment || fragment instanceof edu.xjtlu.civa.feature.ef.HouseholdFragment || !container.sessions.isLoggedIn();
        setNavigationVisible(!auth);
        int tab = tabFor(fragment);
        if (tab != 0) checkedTab = tab;
        checkNavigationItem(checkedTab);
        int dark = androidx.core.content.ContextCompat.getColor(this,edu.xjtlu.civa.core.ui.R.color.civa_welcome_brown);
        int paper = androidx.core.content.ContextCompat.getColor(this,edu.xjtlu.civa.core.ui.R.color.civa_welcome_paper);
        View content=findViewById(android.R.id.content);
        if(introShowing) content.setBackgroundColor(dark);
        else if(auth) content.setBackground(new android.graphics.drawable.Drawable() {
            final android.graphics.Paint paint=new android.graphics.Paint();
            @Override public void draw(android.graphics.Canvas canvas){canvas.drawColor(paper);paint.setColor(dark);canvas.drawRect(0,0,getBounds().width(),getBounds().height()*.26f,paint);}
            @Override public void setAlpha(int alpha) { } @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity(){return android.graphics.PixelFormat.OPAQUE;}
        });
        else content.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,edu.xjtlu.civa.core.ui.R.color.civa_background));
        var bars=androidx.core.view.WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!auth && !introShowing); bars.setAppearanceLightNavigationBars(!introShowing);
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("app.tab", checkedTab); state.putBoolean("app.intro.finished",!introShowing); state.putLong("app.intro.started", intro == null ? introStarted : android.os.SystemClock.elapsedRealtime() - intro.playbackMillis()); state.putBoolean("app.intro.voiced", intro != null && intro.hasVoiced()); super.onSaveInstanceState(state); }

    @Override public Object onRetainCustomNonConfigurationInstance() { return Boolean.TRUE; }

    @Override protected void onResume() {
        super.onResume();
        showLoginIfNeeded();
    }

    private void showLoginIfNeeded() {
        if (!container.sessions.isLoggedIn()) {
            setNavigationVisible(false);
            var current = getSupportFragmentManager().findFragmentById(R.id.app_content);
            if (current != null && !(current instanceof LoginFragment)
                    && !getSupportFragmentManager().isStateSaved()) navigator.navigate(Route.LOGIN, null);
        }
    }

    @Override public AuthRepository authRepository() { return container.auth; }

    @Override public void navigate(Route route, String entityId) { if (route == Route.LOGIN || route == Route.HOUSEHOLD) setNavigationVisible(false); navigator.navigate(route, entityId); }

    @Override public ApiProvider api() { return container.api; }

    @Override public ApiCalls calls() { return container.calls; }

    @Override public SessionStore sessions() { return container.sessions; }

    @Override public java.util.List<edu.xjtlu.civa.core.data.PlannedDish> planFor(java.time.LocalDate date) {
        return container.todayPlan.plan(date);
    }

    @Override public void onHouseholdReady() {
        if (!container.sessions.isLoggedIn() || getSupportFragmentManager().isStateSaved()) return;
        navigate(Route.TODAY, null);
    }

    @Override public void onAuthenticated() {
        if (!container.sessions.isLoggedIn() || getSupportFragmentManager().isStateSaved()) return;
        View focused = getCurrentFocus(); if (focused != null) focused.clearFocus();
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).hide(androidx.core.view.WindowInsetsCompat.Type.ime());
        navigate(container.sessions.currentHouseholdId() == null ? Route.HOUSEHOLD : Route.TODAY, null);
    }
}
