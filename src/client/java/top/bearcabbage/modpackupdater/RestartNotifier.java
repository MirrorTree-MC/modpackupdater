package top.bearcabbage.modpackupdater;

import java.io.IOException;

public class RestartNotifier {

    public static void showUpdateDialog() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) {
                // Windows 使用 msg 命令弹窗（命令行弹窗）
                Runtime.getRuntime().exec(new String[]{
                        "cmd", "/c", "msg", "*", "MirrorTree 更新完成，请重启游戏。"
                });
            } else if (os.contains("mac")) {
                // macOS 使用 AppleScript 弹窗
                String[] cmd = {
                        "osascript", "-e",
                        "display dialog \"MirrorTree 更新完成，请重启游戏。\" buttons {\"确定\"} default button 1"
                };
                Runtime.getRuntime().exec(cmd);
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                // Linux 使用 zenity 工具弹窗（需要安装zenity）
                String[] cmd = {
                        "zenity", "--info", "--text=MirrorTree 更新完成，请重启游戏。"
                };
                Runtime.getRuntime().exec(cmd);
            } else {
                System.out.println("[MirrorTree] 未知系统，无法弹出系统通知，请手动重启游戏。");
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("[MirrorTree] 弹窗通知失败，请手动重启游戏。");
        }
    }
}