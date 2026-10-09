FROM python:3.13-slim
WORKDIR /app
COPY server.py .
COPY discoveries.py .
COPY free_roam.py .
COPY wake_cycles.py .
USER 65534:65534
CMD ["python", "-u", "server.py"]
