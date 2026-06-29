import json
import os
import re
import sys


def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    tokens_path = os.path.join(root_dir, "design_tokens.json")

    if not os.path.exists(tokens_path):
        print(f"Error: design_tokens.json not found at {tokens_path}")
        sys.exit(1)

    with open(tokens_path, "r", encoding="utf-8") as f:
        tokens = json.load(f)

    color_kt_path = os.path.join(
        root_dir,
        "android-app",
        "app",
        "src",
        "main",
        "java",
        "com",
        "company",
        "skolab",
        "ui",
        "theme",
        "Color.kt",
    )
    spacing_kt_path = os.path.join(
        root_dir,
        "android-app",
        "app",
        "src",
        "main",
        "java",
        "com",
        "company",
        "skolab",
        "ui",
        "theme",
        "Spacing.kt",
    )
    shape_kt_path = os.path.join(
        root_dir,
        "android-app",
        "app",
        "src",
        "main",
        "java",
        "com",
        "company",
        "skolab",
        "ui",
        "theme",
        "Shape.kt",
    )
    motion_kt_path = os.path.join(
        root_dir,
        "android-app",
        "app",
        "src",
        "main",
        "java",
        "com",
        "company",
        "skolab",
        "ui",
        "theme",
        "Motion.kt",
    )

    errors = []

    # 1. Verify spacing tokens
    if os.path.exists(spacing_kt_path):
        with open(spacing_kt_path, "r", encoding="utf-8") as f:
            spacing_content = f.read()
        for key, val in tokens["spacing"].items():
            pattern = rf"val {key}:\s*Dp\s*=\s*{re.escape(val)}"
            if not re.search(pattern, spacing_content):
                errors.append(
                    f"Spacing token '{key}' with value '{val}' not found in Spacing.kt"
                )
    else:
        errors.append("Spacing.kt not found")

    # 2. Verify shape tokens
    if os.path.exists(shape_kt_path):
        with open(shape_kt_path, "r", encoding="utf-8") as f:
            shape_content = f.read()
        for key, val in tokens["shapes"].items():
            pattern = rf"val {key}\s*=\s*{re.escape(val)}"
            if not re.search(pattern, shape_content):
                errors.append(
                    f"Shape token '{key}' with value '{val}' not found in Shape.kt"
                )
    else:
        errors.append("Shape.kt not found")

    # 3. Verify motion tokens
    if os.path.exists(motion_kt_path):
        with open(motion_kt_path, "r", encoding="utf-8") as f:
            motion_content = f.read()
        for key, val in tokens["motion"].items():
            pattern = rf"const val {key}\s*=\s*{val}"
            if not re.search(pattern, motion_content):
                errors.append(
                    f"Motion token '{key}' with value '{val}' not found in Motion.kt"
                )
    else:
        errors.append("Motion.kt not found")

    # 4. Verify color tokens
    if os.path.exists(color_kt_path):
        with open(color_kt_path, "r", encoding="utf-8") as f:
            color_content = f.read()
        content_upper = color_content.upper()
        for name, val in tokens["colors"].items():
            name_upper = name.upper()
            light_hex = val["light"].upper().replace("#", "0XFF")
            dark_hex = val["dark"].upper().replace("#", "0XFF")

            pattern_light = f"COLOR({light_hex})"
            pattern_dark = f"COLOR({dark_hex})"

            if pattern_light not in content_upper or pattern_dark not in content_upper:
                errors.append(
                    f"Color token '{name_upper}' (Light: {light_hex}, Dark: {dark_hex}) does not match or is missing in Color.kt"
                )
    else:
        errors.append("Color.kt not found")

    if errors:
        print("Design Token Sync Verification FAILED:")
        for err in errors:
            print(f" - {err}")
        sys.exit(1)
    else:
        print("Design Token Sync Verification SUCCESS: All tokens match perfectly.")
        sys.exit(0)


if __name__ == "__main__":
    main()
