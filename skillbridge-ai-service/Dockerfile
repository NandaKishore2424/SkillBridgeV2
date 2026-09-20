# The AI service, with its model baked in.
#
#   docker build -t skillbridge-ai skillbridge-ai-service/
#
# This is the largest image of the three by a wide margin, and two decisions
# below are what keep it from being very much larger still.

FROM python:3.12-slim AS base
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# ---- dependencies -----------------------------------------------------------
FROM base AS deps
WORKDIR /build

# DECISION 1: CPU-only PyTorch, installed before anything that wants it.
#
# torch arrives transitively through sentence-transformers, and the wheel PyPI
# serves by default bundles the CUDA runtime -- well over a gigabyte of it. The
# EC2 instance this runs on has no GPU, so every byte of that is downloaded,
# stored and backed up to do nothing. PyTorch's own CPU index serves the same
# library without it.
#
# It has to come first: once torch is satisfied, installing requirements.txt
# leaves it alone. Afterwards, pip would have to uninstall the CUDA build to
# replace it, having already downloaded it.
RUN pip install --user torch --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --user -r requirements.txt

# DECISION 2: the MiniLM weights are baked in, not fetched at boot.
#
# ~90 MB from HuggingFace on every cold start is slow, and worse, it makes
# starting this service depend on HuggingFace being reachable -- a third party
# in the critical path of a demo. Baking it in costs image size and buys a
# start that works on a bad hotel connection.
#
# It must be the same model id as config.EMBEDDING_MODEL_NAME, and the same one
# the ETL embedded the corpus with: a different model means vectors in a
# different space, and every similarity score becomes meaningless rather than
# wrong in a way anyone would notice.
ENV PATH=/root/.local/bin:$PATH
RUN python -c "\
from sentence_transformers import SentenceTransformer; \
SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"

# ---- runtime ----------------------------------------------------------------
FROM base AS runtime
RUN useradd --create-home --shell /bin/bash app
WORKDIR /app

COPY --from=deps --chown=app:app /root/.local            /home/app/.local
COPY --from=deps --chown=app:app /root/.cache/huggingface /home/app/.cache/huggingface
COPY --chown=app:app . .

USER app
ENV PATH=/home/app/.local/bin:$PATH \
    HF_HOME=/home/app/.cache/huggingface
EXPOSE 8000

# /health, not /. `/` is liveness and answers 200 while the AMQP consumer is
# dead; /health is 503 unless the consumer is consuming and the database
# answers. A dead consumer is the failure this endpoint exists for -- events
# pile up unread and nothing else notices -- so it is the one to ask.
#
# start-period is generous: loading the model into memory takes several seconds
# and the container is not unhealthy while it does.
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
  CMD python -c "import urllib.request,sys; \
      sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health').status == 200 else 1)"

# One worker, deliberately. The model is about half a gigabyte of process
# memory; four workers is four copies of it, which does not fit on the
# instance this runs on. Concurrency here is bounded by the database pool
# (two connections) and the analysis is seconds of CPU, so more workers on one
# small box would queue on the same cores anyway. Scale by adding containers,
# not workers -- and nothing about this deployment needs to.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
