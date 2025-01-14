/*
 * WebAuthProvider.java
 *
 * Copyright (c) 2016 Auth0 (http://auth0.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.auth0.android.provider;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.auth0.android.Auth0;
import com.auth0.android.Auth0Exception;
import com.auth0.android.auth0.R;
import com.auth0.android.authentication.AuthenticationException;

import java.util.HashMap;
import java.util.Map;

import static com.auth0.android.provider.OAuthManager.KEY_CONNECTION;
import static com.auth0.android.provider.OAuthManager.KEY_NONCE;
import static com.auth0.android.provider.OAuthManager.KEY_RESPONSE_TYPE;
import static com.auth0.android.provider.OAuthManager.KEY_STATE;
import static com.auth0.android.provider.OAuthManager.RESPONSE_TYPE_CODE;
import static com.auth0.android.provider.OAuthManager.RESPONSE_TYPE_ID_TOKEN;

/**
 * OAuth2 Web Authentication Provider.
 * It can use an external browser by sending the {@link android.content.Intent#ACTION_VIEW} intent or also the {@link WebAuthActivity}.
 * This behaviour is changed using {@link WebAuthProvider.Builder#useBrowser(boolean)}, and defaults to use browser.
 */
@SuppressWarnings("WeakerAccess")
public class WebAuthProvider {

    private static final String TAG = WebAuthProvider.class.getName();

    private static ResumableManager managerInstance;

    public static class LogoutBuilder {

        private final Auth0 account;
        private String scheme;
        private CustomTabsOptions ctOptions;

        LogoutBuilder(Auth0 account) {
            this.account = account;

            //Default values
            this.scheme = "https";
        }

        /**
         * When using a Custom Tabs compatible Browser, apply these customization options.
         *
         * @param options the Custom Tabs customization options
         * @return the current builder instance
         */
        public LogoutBuilder withCustomTabsOptions(@NonNull CustomTabsOptions options) {
            this.ctOptions = options;
            return this;
        }

        /**
         * Specify a custom Scheme to use on the Return To Uri. Default scheme is 'https'.
         *
         * @param scheme to use in the Callback Uri.
         * @return the current builder instance
         */
        public LogoutBuilder withScheme(@NonNull String scheme) {
            String lowerCase = scheme.toLowerCase();
            if (!scheme.equals(lowerCase)) {
                Log.w(TAG, "Please provide the scheme in lowercase and make sure it's the same configured in the intent filter. Android expects the scheme to be lowercase.");
            }
            this.scheme = scheme;
            return this;
        }

        /**
         * Request the user session to be cleared. When successful, the callback will get invoked
         *
         * @param context  to run the log out
         * @param callback to invoke when log out is successful
         */
        public void start(Context context, VoidCallback callback) {
            resetManagerInstance();

            if (!hasBrowserAppInstalled(context.getPackageManager())) {
                Throwable cause = new ActivityNotFoundException("No Browser application installed.");
                final Auth0Exception ex = new Auth0Exception("Cannot perform web log out", cause);
                callback.onFailure(ex);
                return;
            }

            String returnToUrl = CallbackHelper.getCallbackUri(scheme, context.getApplicationContext().getPackageName(), account.getDomainUrl());
            LogoutManager logoutManager = new LogoutManager(this.account, callback, returnToUrl);
            logoutManager.setCustomTabsOptions(ctOptions);

            managerInstance = logoutManager;
            logoutManager.startLogout(context);
        }
    }

    public static class Builder {

        private static final String KEY_AUDIENCE = "audience";
        private static final String KEY_SCOPE = "scope";
        private static final String KEY_CONNECTION_SCOPE = "connection_scope";
        private static final String SCOPE_TYPE_OPENID = "openid";
        private static final String RESPONSE_TYPE_TOKEN = "token";

        private final Auth0 account;
        private final Map<String, String> values;
        private boolean useBrowser;
        private boolean useFullscreen;
        private PKCE pkce;
        private String scheme;
        private CustomTabsOptions ctOptions;

        Builder(Auth0 account) {
            this.account = account;
            this.values = new HashMap<>();

            //Default values
            this.scheme = "https";
            this.useBrowser = true;
            this.useFullscreen = false;
            withResponseType(ResponseType.CODE);
            withScope(SCOPE_TYPE_OPENID);
        }

        /**
         * If the class authenticates with an external browser or not.
         *
         * @param useBrowser if the authentication is handled in a Browser.
         * @return the current builder instance
         * @deprecated This method has been deprecated since it only applied to WebView authentication and Google is no longer supporting it. You should use the default value (use browser).
         */
        @SuppressWarnings("DeprecatedIsStillUsed")
        @Deprecated
        public Builder useBrowser(boolean useBrowser) {
            this.useBrowser = useBrowser;
            return this;
        }

        /**
         * If the activity should be fullscreen or not. Applies only to the WebView activity, not to the
         * Browser authentication.
         *
         * @param useFullscreen if the activity should be fullscreen or not.
         * @return the current builder instance
         * @deprecated This method has been deprecated since it only applied to WebView authentication and Google is no longer supporting it.
         */
        @Deprecated
        public Builder useFullscreen(boolean useFullscreen) {
            this.useFullscreen = useFullscreen;
            return this;
        }

        /**
         * Use a custom state in the requests
         *
         * @param state to use in the requests
         * @return the current builder instance
         */
        public Builder withState(@NonNull String state) {
            this.values.put(KEY_STATE, state);
            return this;
        }

        /**
         * Specify a custom nonce value to avoid replay attacks. It will be sent in the auth request that will be returned back as a claim in the id_token
         *
         * @param nonce to use in the requests
         * @return the current builder instance
         */
        public Builder withNonce(@NonNull String nonce) {
            this.values.put(KEY_NONCE, nonce);
            return this;
        }

        /**
         * Use a custom audience in the requests
         *
         * @param audience to use in the requests
         * @return the current builder instance
         */
        public Builder withAudience(@NonNull String audience) {
            this.values.put(KEY_AUDIENCE, audience);
            return this;
        }

        /**
         * Specify a custom Scheme to use on the Callback Uri. Default scheme is 'https'.
         *
         * @param scheme to use in the Callback Uri.
         * @return the current builder instance
         */
        public Builder withScheme(@NonNull String scheme) {
            String lowerCase = scheme.toLowerCase();
            if (!scheme.equals(lowerCase)) {
                Log.w(TAG, "Please provide the scheme in lowercase and make sure it's the same configured in the intent filter. Android expects the scheme to be lowercase.");
            }
            this.scheme = scheme;
            return this;
        }

        /**
         * Give a scope for this request.
         *
         * @param scope to request.
         * @return the current builder instance
         */
        public Builder withScope(@NonNull String scope) {
            this.values.put(KEY_SCOPE, scope);
            return this;
        }

        /**
         * Give a connection scope for this request.
         *
         * @param connectionScope to request.
         * @return the current builder instance
         */
        public Builder withConnectionScope(@NonNull String... connectionScope) {
            StringBuilder sb = new StringBuilder();
            for (String s : connectionScope) {
                sb.append(s.trim()).append(",");
            }
            if (sb.length() > 0) {
                sb.deleteCharAt(sb.length() - 1);
                this.values.put(KEY_CONNECTION_SCOPE, sb.toString());
            }
            return this;
        }

        /**
         * Choose the grant type for this request.
         *
         * @param useCodeGrant whether use code or implicit grant type
         * @return the current builder instance
         * @deprecated Please use {@link WebAuthProvider.Builder#withResponseType(int)} to specify a custom Response Type
         */
        @Deprecated
        public Builder useCodeGrant(boolean useCodeGrant) {
            withResponseType(useCodeGrant ? ResponseType.CODE : ResponseType.TOKEN);
            return this;
        }

        /**
         * Choose the grant type for this request.
         *
         * @param type the ResponseType to request to the Authentication API. Multiple ResponseType's can be defined using a pipe. "CODE | TOKEN"
         * @return the current builder instance
         */
        public Builder withResponseType(@ResponseType int type) {
            StringBuilder sb = new StringBuilder();
            if (FlagChecker.hasFlag(type, ResponseType.CODE)) {
                sb.append(RESPONSE_TYPE_CODE).append(" ");
            }
            if (FlagChecker.hasFlag(type, ResponseType.ID_TOKEN)) {
                sb.append(RESPONSE_TYPE_ID_TOKEN).append(" ");
            }
            if (FlagChecker.hasFlag(type, ResponseType.TOKEN)) {
                sb.append(RESPONSE_TYPE_TOKEN);
            }
            this.values.put(KEY_RESPONSE_TYPE, sb.toString().trim());
            return this;
        }

        /**
         * Use extra parameters on the request.
         *
         * @param parameters to add
         * @return the current builder instance
         */
        public Builder withParameters(@NonNull Map<String, Object> parameters) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (entry.getValue() != null) {
                    this.values.put(entry.getKey(), entry.getValue().toString());
                }
            }
            return this;
        }

        /**
         * Use the given connection. By default no connection is specified, so the login page will be displayed.
         *
         * @param connectionName to use
         * @return the current builder instance
         */
        public Builder withConnection(@NonNull String connectionName) {
            this.values.put(KEY_CONNECTION, connectionName);
            return this;
        }

        /**
         * When using a Custom Tabs compatible Browser, apply these customization options.
         *
         * @param options the Custom Tabs customization options
         * @return the current builder instance
         */
        public Builder withCustomTabsOptions(@NonNull CustomTabsOptions options) {
            this.ctOptions = options;
            return this;
        }

        @VisibleForTesting
        Builder withPKCE(PKCE pkce) {
            this.pkce = pkce;
            return this;
        }

        /**
         * Request user Authentication. The result will be received in the callback.
         *
         * @param activity    context to run the authentication
         * @param callback    to receive the parsed results
         * @param requestCode to use in the authentication request
         * @deprecated This method has been deprecated since it only applied to WebView authentication and Google is no longer supporting it. Please use {@link WebAuthProvider.Builder#start(Activity, AuthCallback)}
         */
        @SuppressLint("VisibleForTests")
        @Deprecated
        public void start(@NonNull Activity activity, @NonNull AuthCallback callback, int requestCode) {
            resetManagerInstance();

            if (useBrowser && !hasBrowserAppInstalled(activity.getPackageManager())) {
                AuthenticationException ex = new AuthenticationException("a0.browser_not_available", "No Browser application installed to perform web authentication.");
                callback.onFailure(ex);
                return;
            }

            OAuthManager manager = new OAuthManager(account, callback, values);
            manager.useFullScreen(useFullscreen);
            manager.useBrowser(useBrowser);
            manager.setCustomTabsOptions(ctOptions);
            manager.setPKCE(pkce);

            managerInstance = manager;

            String redirectUri = CallbackHelper.getCallbackUri(scheme, activity.getApplicationContext().getPackageName(), account.getDomainUrl());
            manager.startAuthentication(activity, redirectUri, requestCode);
        }

        /**
         * Request user Authentication. The result will be received in the callback.
         *
         * @param activity context to run the authentication
         * @param callback to receive the parsed results
         */
        public void start(@NonNull Activity activity, @NonNull AuthCallback callback) {
            this.start(activity, callback, 110);
        }
    }

    // Public methods

    /**
     * Initialize the WebAuthProvider instance for logging out the user using an account. Additional settings can be configured
     * in the LogoutBuilder, like changing the scheme of the return to URL.
     *
     * @param account to use for authentication
     * @return a new Builder instance to customize.
     */
    public static LogoutBuilder logout(@NonNull Auth0 account) {
        return new LogoutBuilder(account);
    }

    /**
     * Initialize the WebAuthProvider instance for authenticating the user using an account. Additional settings can be configured
     * in the Builder, like setting the connection name or authentication parameters.
     *
     * @param account to use for authentication
     * @return a new Builder instance to customize.
     */
    public static Builder login(@NonNull Auth0 account) {
        return new Builder(account);
    }

    /**
     * Initialize the WebAuthProvider instance for authenticating the user using an account. Additional settings can be configured
     * in the Builder, like setting the connection name or authentication parameters.
     *
     * @param account to use for authentication
     * @return a new Builder instance to customize.
     * @deprecated This method was renamed to reflect an authentication flow. Please use {@link #login(Auth0)}.
     */
    @Deprecated
    public static Builder init(@NonNull Auth0 account) {
        return login(account);
    }

    /**
     * Initialize the WebAuthProvider instance with an Android Context. Additional settings can be configured
     * in the Builder, like setting the connection name or authentication parameters.
     *
     * @param context a valid context.
     * @return a new Builder instance to customize.
     * @deprecated This method was renamed to reflect an authentication flow. Please use {@link #login(Auth0)}. You can create an Auth0 instance from a Context using {@link Auth0#Auth0(Context)}.
     */
    @Deprecated
    public static Builder init(@NonNull Context context) {
        return login(new Auth0(context));
    }

    /**
     * Finishes the authentication or log out flow by passing the data received in the activity's onActivityResult() callback.
     * The final result will be delivered to the callback specified when calling start().
     * <p>
     * This is no longer required to be called, the redirect is handled internally as long as you've correctly setup the intent-filter.
     *
     * @param requestCode the request code received on the onActivityResult() call
     * @param resultCode  the result code received on the onActivityResult() call
     * @param intent      the data received on the onActivityResult() call
     * @return true if a result was expected and has a valid format, or false if not. When true is returned a call on the callback is expected.
     * @deprecated This method has been deprecated since it only applied to WebView authentication and Google is no longer supporting it. Please use {@link WebAuthProvider#resume(Intent)}
     */
    @Deprecated
    public static boolean resume(int requestCode, int resultCode, @Nullable Intent intent) {
        if (managerInstance == null) {
            Log.w(TAG, "There is no previous instance of this provider.");
            return false;
        }
        final AuthorizeResult result = new AuthorizeResult(requestCode, resultCode, intent);
        boolean success = managerInstance.resume(result);
        if (success) {
            resetManagerInstance();
        }
        return success;
    }

    /**
     * Finishes the authentication or log out flow by passing the data received in the activity's onNewIntent() callback.
     * The final result will be delivered to the callback specified when calling start().
     * <p>
     * This is no longer required to be called, the authentication is handled internally as long as you've correctly setup the intent-filter.
     *
     * @param intent the data received on the onNewIntent() call. When null is passed, the authentication will be considered canceled.
     * @return true if a result was expected and has a valid format, or false if not. When true is returned a call on the callback is expected.
     */
    public static boolean resume(@Nullable Intent intent) {
        if (managerInstance == null) {
            Log.w(TAG, "There is no previous instance of this provider.");
            return false;
        }

        final AuthorizeResult result = new AuthorizeResult(intent);
        boolean success = managerInstance.resume(result);
        if (success) {
            resetManagerInstance();
        }
        return success;

    }

    // End Public methods

    @VisibleForTesting
    static ResumableManager getManagerInstance() {
        return managerInstance;
    }

    @VisibleForTesting
    static void resetManagerInstance() {
        managerInstance = null;
    }

    @VisibleForTesting
    static boolean hasBrowserAppInstalled(@NonNull PackageManager packageManager) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://auth0.com"));
        return intent.resolveActivity(packageManager) != null;
    }
}
