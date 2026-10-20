"""SkoLab's AI-writing detector on Modal (modal.com), from the free monthly
credits: it runs only while asked and scales to zero between checks.

    modal secret create skolab-detector DETECTOR_TOKEN=<secret>
    modal deploy services/ai-detector/modal_app.py

The web endpoint is app.py's FastAPI app unchanged: POST /detect (requires
the X-Detector-Token secret) and GET /health. The model's pinned revision is
baked into the image, so a cold start only loads it (about 30 s on 2 CPUs).
The API calls it with AI_DETECTOR_URL (the deployed URL, printed by
`modal deploy`) and AI_DETECTOR_TOKEN.
"""

from pathlib import Path

import modal

HERE = Path(__file__).resolve().parent
MODEL = "desklib/ai-text-detector-v1.01"
REVISION = "5fdea974cd4287c61674951ec78803aa274e2fb7"


def download() -> None:
    from huggingface_hub import snapshot_download

    snapshot_download(MODEL, revision=REVISION)


image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install_from_requirements(
        str(HERE / "requirements.txt"),
        extra_index_url="https://download.pytorch.org/whl/cpu",
    )
    .run_function(download)
    .add_local_file(str(HERE / "app.py"), "/root/app.py")
)

app = modal.App("skolab-ai-detector")


@app.function(
    image=image,
    cpu=2.0,
    memory=4096,
    # Stay up a few minutes after a check (its windows arrive together), then
    # scale to zero so idle time costs nothing.
    scaledown_window=180,
    timeout=600,
    max_containers=2,
    secrets=[modal.Secret.from_name("skolab-detector")],
)
@modal.concurrent(max_inputs=4)
@modal.asgi_app(label="skolab-ai-detector")
def web():
    import sys

    sys.path.insert(0, "/root")
    from app import app as fastapi_app
    from app import load

    load()  # cold start: load the model before the first request is served
    return fastapi_app
