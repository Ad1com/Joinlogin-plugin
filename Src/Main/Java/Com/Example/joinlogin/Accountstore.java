package com.example.joinlogin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

final class AccountStore {

    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml;

    AccountStore(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "accounts.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    synchronized boolean isRegistered(UUID id) {
        return yaml.contains("accounts." + id + ".hash");
    }

    void register(UUID id, String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        String hashB64 = Base64.getEncoder().encodeToString(hash(password, salt));
        synchronized (this) {
            yaml.set("accounts." + id + ".salt", saltB64);
            yaml.set("accounts." + id + ".hash", hashB64);
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save accounts.yml: " + e.getMessage());
            }
        }
    }

    boolean verify(UUID id, String password) {
        String saltB64;
        String hashB64;
        synchronized (this) {
            saltB64 = yaml.getString("accounts." + id + ".salt");
            hashB64 = yaml.getString("accounts." + id + ".hash");
        }
        if (saltB64 == null || hashB64 == null) return false;
        byte[] actual = hash(password, Base64.getDecoder().decode(saltB64));
        return MessageDigest.isEqual(actual, Base64.getDecoder().decode(hashB64));
    }

    private static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing failed", e);
        }
    }
}
