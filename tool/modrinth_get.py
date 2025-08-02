
import requests
import json
from pathlib import Path
from tqdm import tqdm
import zipfile
import io

slug_list = [
    # "adventurez",
    # "alloy-forgery",
    # "annoying-effects",
    # "appleskin",
    # "architectury-api",
    # "biomes-o-plenty",
    # "bosses-of-mass-destruction",
    # "cardinal-components-api",
    # "chat-heads",
    # "chunky",
    # "cloth-config",
    # "colorful-subtitles",
    # "continuity",
    # "customskinloader",
    # "fabric-api",
    # "fabric-language-kotlin",
    # "farmers-delight-refabricated",
    # "forge-config-api-port",
    # "geckolib",
    # "glitchcore",
    # "inventory-profiles-next",
    # "iris",
    # "just-zoom",
    # "konkrete",
    # "lambdynamiclights",
    # "libipn",
    # "litematica",
    # "lithium",
    # "malilib",
    # "minihud",
    # "modern-ui",
    # "modmenu",
    # "mutils",
    # "mythicmetals",
    # "neat",
    # "owo-lib",
    # "patchouli",
    # "presence-footsteps",
    # "reactive-music",
    # "reeses-sodium-options",
    # "rei",
    # "serene-seasons",
    # "bl4cks-sit",
    # "sodium-extra",
    # "sodium",
    # "spark",
    # "terrablender",
    # "universal-shops",
    # "universal-graves",
    # "vmp-fabric",
    "xaeros-minimap",
    "xaeros-world-map",
    "yacl",
]

DATA_DIR = Path(__file__).parent.parent / "data"
MODLIST_JSON = DATA_DIR / "modpack_update_manifest.json"
MANUAL_LIST_JSON = DATA_DIR / "manual_links.json"



def get_fabric_mod_id_from_jar(jar_url):
    try:
        resp = requests.get(jar_url, timeout=30)
        with zipfile.ZipFile(io.BytesIO(resp.content)) as zf:
            with zf.open("fabric.mod.json") as f:
                mod_json = json.load(f)
                return mod_json.get("id", None), mod_json.get("version", None)
    except Exception:
        return None, None

def get_modrinth_mod_info(slug):
    url = f"https://api.modrinth.com/v2/project/{slug}/version"
    try:
        response = requests.get(url)
        data = response.json()
    except requests.RequestException:
        return None
    filtered_versions = [
        v
        for v in data
        if "1.21.1" in v.get("game_versions", []) and "fabric" in v.get("loaders", [])
    ]
    if filtered_versions:
        latest_version = filtered_versions[0]
        file_info = latest_version["files"][0]
        mod_url = file_info["url"]
        fabric_id, fabric_version = get_fabric_mod_id_from_jar(mod_url)
        return {
            "path": f"mods/{file_info['filename']}",
            "url": mod_url,
            "mode": "version",
            "id": fabric_id or latest_version.get("project_id", slug),
            "type": "mod",
            "version": fabric_version or latest_version["version_number"],
        }
    return None


def load_json_files_list(json_path):
    try:
        with open(json_path, "r", encoding="utf-8") as f:
            data = json.load(f)
            if isinstance(data, dict) and "files" in data:
                return data["files"]
            elif isinstance(data, list):
                return data
    except Exception:
        pass
    return []


def main():
    old_mods = load_json_files_list(MODLIST_JSON)
    old_mods_map = {m.get("path", ""): m for m in old_mods}
    new_mods = []
    update_counter = 0

    for slug in tqdm(slug_list, desc="Modrinth 查询进度"):
        mod_info = get_modrinth_mod_info(slug)
        if mod_info:
            old = old_mods_map.get(mod_info["path"])
            if old and old.get("version") == mod_info["version"]:
                new_mods.append(old)
            else:
                new_mods.append(mod_info)
                update_counter += 1

    # manual_links.json 合并，manual 优先覆盖自动生成
    manual_data = {}
    try:
        with open(MANUAL_LIST_JSON, "r", encoding="utf-8") as f:
            manual_data = json.load(f)
        print(
            f"已加载手动维护的文件下载链接，共 {len(manual_data.get('files', []))} 个条目。"
        )
    except Exception:
        manual_data = {}

    manual_mods = manual_data.get("files", [])
    manual_delete = manual_data.get("delete", [])

    # 合并mod文件，manual优先覆盖自动生成
    mods_map = {m.get("path", ""): m for m in new_mods}
    for m in manual_mods:
        p = m.get("path", "")
        if p:
            mods_map[p] = m

    all_mods = list(mods_map.values())
    all_delete = list({d for d in manual_delete})

    for item in all_mods:
        if item.get("type") != "mod":
            item["id"] = "none"

    with open(MODLIST_JSON, "w", encoding="utf-8") as f:
        json.dump({"files": all_mods, "delete": all_delete}, f, indent=4, ensure_ascii=False)

    print(f"已保存 {len(all_mods)} 个 mod 到 {MODLIST_JSON}")
    print(f"更新了 {update_counter} 个 mod 的版本信息。")


if __name__ == "__main__":
    main()
