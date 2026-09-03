FROM python:3.12-slim

# curl: python:3.12-slim doesn't include it by default, but docker-compose.yml's healthcheck
# for this service needs it (no other HTTP client is guaranteed present in this image).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
