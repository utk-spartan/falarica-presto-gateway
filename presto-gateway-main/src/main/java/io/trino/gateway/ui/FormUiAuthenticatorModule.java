/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ui;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Scopes;
import io.trino.server.security.Authenticator;
import io.trino.server.security.PasswordAuthenticatorManager;

import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;

public class FormUiAuthenticatorModule
        implements Module
{
    @Override
    public void configure(Binder binder)
    {
        binder.bind(PasswordAuthenticatorManager.class).in(Scopes.SINGLETON);
        binder.bind(WebUiAuthenticationManager.class).to(FormWebUiAuthenticationManager.class).in(Scopes.SINGLETON);
        configBinder(binder).bindConfig(FormWebUiConfig.class);
        newOptionalBinder(binder, Key.get(Authenticator.class, ForWebUi.class));
    }
}
