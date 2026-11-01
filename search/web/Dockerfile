FROM python:3.12-alpine
WORKDIR /app
COPY server.py page.html /app/
ENV PYTHONDONTWRITEBYTECODE=1
EXPOSE 8080
USER 10001
CMD ["python", "/app/server.py"]
