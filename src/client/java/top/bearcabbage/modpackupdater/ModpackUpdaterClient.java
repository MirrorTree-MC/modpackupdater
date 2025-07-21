package top.bearcabbage.modpackupdater;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;

import static com.mojang.text2speech.Narrator.LOGGER;

public class ModpackUpdaterClient implements ClientModInitializer {
	public static boolean updating = false;
	public static boolean updated = false;

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(ModpackUpdaterClient::onStarted);
		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			if (MinecraftClient.getInstance().options.hudHidden) return;

			MinecraftClient client = MinecraftClient.getInstance();
			int screenWidth = context.getScaledWindowWidth();
			int screenHeight = context.getScaledWindowHeight();

			int boxWidth = 160;
			int boxHeight = 40;
			int margin = 10;
			int x = screenWidth - boxWidth - margin;
			int y = screenHeight - boxHeight - margin;

			if (ModpackUpdaterClient.updating) {
				context.fill(x, y, x + boxWidth, y + boxHeight, 0xAA000000); // 半透明背景
				context.drawText(client.textRenderer, "正在检查更新...", x + 8, y + 12, 0xFFFFFF, true);
			} else if (ModpackUpdaterClient.updated) {
				context.fill(x, y, x + boxWidth, y + boxHeight, 0xCC000000);
				context.drawText(client.textRenderer, "更新完成，请重启游戏", x + 8, y + 12, 0xFF5555, true);
			}
		});
	}

	public static void onStarted(MinecraftClient client) {
		updating = true;
		try {
			IncrementalUpdater.runUpdate("https://gitee.com/integrity_k/.github-private/releases/download/latest/modpack_update_manifest.json");
		} catch (IOException e) {
			LOGGER.error("[MirrorTree]Updating error: " + e.getMessage());
		}
		updating = false;
		if (ModpackUpdaterClient.updated) {
			RestartNotifier.showUpdateDialog();
			MinecraftClient.getInstance().stop();
			throw new RuntimeException("[MirrorTree] 客户端更新完成，请重启。");
		}

	}
}