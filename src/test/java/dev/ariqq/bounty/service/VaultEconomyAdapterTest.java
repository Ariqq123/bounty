package dev.ariqq.bounty.service;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VaultEconomyAdapterTest {
    private static final Field SERVER_FIELD = resolveServerField();

    private Server previousServer;

    @AfterEach
    void restoreServer() throws IllegalAccessException {
        SERVER_FIELD.set(null, previousServer);
    }

    @Test
    void depositUsesUuidResolvedOfflinePlayerEvenWhenNameLookupPointsElsewhere() throws IllegalAccessException {
        UUID playerUuid = UUID.randomUUID();
        UUID wrongUuid = UUID.randomUUID();
        OfflinePlayer uuidPlayer = offlinePlayer(playerUuid, null);
        OfflinePlayer namePlayer = offlinePlayer(wrongUuid, "Hunter");
        AtomicReference<OfflinePlayer> depositedPlayer = new AtomicReference<>();

        previousServer = Bukkit.getServer();
        SERVER_FIELD.set(null, server(uuidPlayer, namePlayer));

        VaultEconomyAdapter adapter = new VaultEconomyAdapter(economyProxy(depositedPlayer, new AtomicReference<>()));
        boolean result = adapter.deposit(playerUuid, "Hunter", 250D);

        Assertions.assertTrue(result);
        Assertions.assertSame(uuidPlayer, depositedPlayer.get());
    }

    @Test
    void withdrawUsesUuidResolvedOfflinePlayerEvenWhenNameLookupPointsElsewhere() throws IllegalAccessException {
        UUID playerUuid = UUID.randomUUID();
        UUID wrongUuid = UUID.randomUUID();
        OfflinePlayer uuidPlayer = offlinePlayer(playerUuid, null);
        OfflinePlayer namePlayer = offlinePlayer(wrongUuid, "Hunter");
        AtomicReference<OfflinePlayer> withdrawnPlayer = new AtomicReference<>();

        previousServer = Bukkit.getServer();
        SERVER_FIELD.set(null, server(uuidPlayer, namePlayer));

        VaultEconomyAdapter adapter = new VaultEconomyAdapter(economyProxy(new AtomicReference<>(), withdrawnPlayer));
        boolean result = adapter.withdraw(playerUuid, "Hunter", 250D);

        Assertions.assertTrue(result);
        Assertions.assertSame(uuidPlayer, withdrawnPlayer.get());
    }

    private Server server(OfflinePlayer uuidPlayer, OfflinePlayer namePlayer) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method.getName(), args);
            }
            return switch (method.getName()) {
                case "getOfflinePlayer" -> {
                    Object key = args[0];
                    if (key instanceof UUID) {
                        yield uuidPlayer;
                    }
                    if (key instanceof String) {
                        yield namePlayer;
                    }
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        };
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class}, handler);
    }

    private Economy economyProxy(AtomicReference<OfflinePlayer> depositedPlayer, AtomicReference<OfflinePlayer> withdrawnPlayer) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method.getName(), args);
            }
            return switch (method.getName()) {
                case "depositPlayer" -> {
                    depositedPlayer.set((OfflinePlayer) args[0]);
                    yield new EconomyResponse((double) args[1], 0D, EconomyResponse.ResponseType.SUCCESS, "");
                }
                case "withdrawPlayer" -> {
                    withdrawnPlayer.set((OfflinePlayer) args[0]);
                    yield new EconomyResponse((double) args[1], 0D, EconomyResponse.ResponseType.SUCCESS, "");
                }
                case "has" -> true;
                default -> defaultValue(method.getReturnType());
            };
        };
        return (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[] {Economy.class}, handler);
    }

    private OfflinePlayer offlinePlayer(UUID uuid, String name) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method.getName(), args);
            }
            return switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> name;
                default -> defaultValue(method.getReturnType());
            };
        };
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[] {OfflinePlayer.class}, handler);
    }

    private Object handleObjectMethod(Object proxy, String methodName, Object[] args) {
        return switch (methodName) {
            case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        return null;
    }

    private static Field resolveServerField() {
        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
