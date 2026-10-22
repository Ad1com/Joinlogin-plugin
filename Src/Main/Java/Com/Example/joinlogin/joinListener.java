package com.example.joinlogin;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.PlayerConnectionCloseEvent;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class JoinListener implements Listener {

    private static final Key SUBMIT = Key.key("joinlogin", "submit");
    private static final Key QUIT = Key.key("joinlogin", "quit");

    private record Response(boolean quit, String password, String confirm) {}

    private final JoinLoginPlugin plugin;
    private final AccountStore accounts;
    private final Map<UUID, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();

    JoinListener(JoinLoginPlugin plugin, AccountStore accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        UUID id = connection.getProfile().getId();
        if (id == null) return;

        Audience audience = connection.getAudience();
        boolean registered = accounts.isRegistered(id);
        int maxAttempts = plugin.getConfig().getInt("max-attempts", 3);
        int timeout = plugin.getConfig().getInt("timeout-seconds", 120);
        int minLength = plugin.getConfig().getInt("min-password-length", 6);

        int failures = 0;
        Component error = null;

        while (failures < maxAttempts) {
            CompletableFuture<Response> future = new CompletableFuture<>();
            pending.put(id, future);
            audience.showDialog(buildDialog(registered, error));

            Response response;
            try {
                response = future.get(timeout, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                connection.disconnect(Component.text("Login timed out.", NamedTextColor.RED));
                return;
            } catch (InterruptedException | ExecutionException | CancellationException e) {
                return;
            } finally {
                pending.remove(id, future);
            }

            if (response.quit()) {
                connection.disconnect(Component.text("You left the login screen.", NamedTextColor.RED));
                return;
            }

            if (registered) {
                if (accounts.verify(id, response.password())) {
                    return;
                }
                failures++;
                error = Component.text("Wrong password! Attempts left: " + (maxAttempts - failures), NamedTextColor.RED);
            } else {
                if (response.password().length() < minLength) {
                    error = Component.text("Password must be at least " + minLength + " characters.", NamedTextColor.RED);
                } else if (!response.password().equals(response.confirm())) {
                    error = Component.text("Passwords do not match.", NamedTextColor.RED);
                } else {
                    accounts.register(id, response.password());
                    return;
                }
            }
        }

        connection.disconnect(Component.text("Too many wrong passwords.", NamedTextColor.RED));
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)) return;

        Key key = event.getIdentifier();
        if (!key.equals(SUBMIT) && !key.equals(QUIT)) return;

        UUID id = connection.getProfile().getId();
        if (id == null) return;
        CompletableFuture<Response> future = pending.get(id);
        if (future == null) return;

        if (key.equals(QUIT)) {
            future.complete(new Response(true, "", ""));
            return;
        }

        DialogResponseView view = event.getDialogResponseView();
        String password = view == null ? null : view.getText("password");
        String confirm = view == null ? null : view.getText("confirm");
        future.complete(new Response(false, password == null ? "" : password, confirm == null ? "" : confirm));
    }

    @EventHandler
    public void onClose(PlayerConnectionCloseEvent event) {
        CompletableFuture<Response> future = pending.remove(event.getPlayerUniqueId());
        if (future != null) future.complete(new Response(true, "", ""));
    }

    private Dialog buildDialog(boolean registered, Component error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(registered
                ? "Welcome back! Enter your password to join."
                : "Welcome! Create a password to register.")));
        if (error != null) body.add(DialogBody.plainMessage(error));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("password", Component.text("Password")).width(250).maxLength(64).build());
        if (!registered) {
            inputs.add(DialogInput.text("confirm", Component.text("Confirm password")).width(250).maxLength(64).build());
        }

        ActionButton submit = ActionButton.create(
                Component.text(registered ? "Login" : "Register", NamedTextColor.GREEN),
                null, 120, DialogAction.customClick(SUBMIT, null));
        ActionButton quit = ActionButton.create(
                Component.text("Leave", NamedTextColor.RED),
                null, 120, DialogAction.customClick(QUIT, null));

        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(registered ? "Login" : "Register"))
                        .canCloseWithEscape(false)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.confirmation(submit, quit)));
    }
}
