FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libsndfile1 libgomp1 libpq5 \
    && rm -rf /var/lib/apt/lists/*
COPY requirements*.txt ./
# CPU image by default; select a matching CUDA torch build for GPU deployment.
RUN pip install --no-cache-dir torch==2.14.1 torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements-api.txt
COPY src ./src
RUN useradd --create-home --uid 10001 soundmatch
USER soundmatch
EXPOSE 8000
CMD ["uvicorn", "src.api.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
