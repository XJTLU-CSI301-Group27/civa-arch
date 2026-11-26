package edu.xjtlu.civa.server.auth;

import edu.xjtlu.civa.server.api.AuthApi;
import edu.xjtlu.civa.server.api.ProfileApi;
import edu.xjtlu.civa.server.api.model.AuthSession;
import edu.xjtlu.civa.server.api.model.LoginRequest;
import edu.xjtlu.civa.server.api.model.Preference;
import edu.xjtlu.civa.server.api.model.RegisterRequest;
import edu.xjtlu.civa.server.api.model.UpdatePreferenceRequest;
import edu.xjtlu.civa.server.api.model.UpdateProfileRequest;
import edu.xjtlu.civa.server.api.model.User;
import edu.xjtlu.civa.server.profile.PreferencesService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController implements AuthApi, ProfileApi {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private final AuthService auth;
    private final PreferencesService preferences;
    private final HttpServletRequest request;

    AuthController(AuthService auth, PreferencesService preferences, HttpServletRequest request) {
        this.auth = auth;
        this.preferences = preferences;
        this.request = request;
    }

    @Override public ResponseEntity<User> register(RegisterRequest input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toWire(auth.register(
                input.getUsername(), input.getDisplayName(), input.getPassword(), input.getZoneId())));
    }

    @Override public ResponseEntity<AuthSession> login(LoginRequest input) {
        AuthService.Login login = auth.login(input.getUsername(), input.getPassword());
        return ResponseEntity.ok(new AuthSession(login.token, time(login.expiresAt), toWire(login.user)));
    }

    @Override public ResponseEntity<Void> logout() {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) auth.logout(header.substring(7).trim());
        return ResponseEntity.noContent().build();
    }

    @Override public ResponseEntity<User> getMe() {
        UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.ok(toWire(auth.findUser(userId)));
    }

    @Override public ResponseEntity<User> updateProfile(UpdateProfileRequest input) {
        UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.ok(toWire(auth.updateDisplayName(userId, input.getDisplayName())));
    }

    @Override public ResponseEntity<Preference> getPreferences() {
        return ResponseEntity.ok(preferences.get(actor()));
    }

    @Override public ResponseEntity<Preference> updatePreferences(UpdatePreferenceRequest input) {
        return ResponseEntity.ok(preferences.update(actor(), input));
    }

    private static UUID actor() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private static User toWire(AuthService.User user) {
        return new User(user.id, time(user.createdAt), time(user.updatedAt), user.username, user.displayName, user.zoneId, user.householdId);
    }

    private static String time(Instant instant) { return TIME.format(instant); }

}
