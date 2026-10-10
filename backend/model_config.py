"""Shared model selection and explicitly reviewed prices for paid autonomous calls."""
import os

DEFAULT_MODEL = "gpt-6.1-sol"
# Prices in tenths of a micro-dollar per token; Standard processing, checked 2026-10-10.
# New models need reviewed prices and filtered-search support before autonomous use.
WAKE_MODELS = {
    "gpt-6.1-sol": {"inputTenths": 20, "outputTenths": 100, "reservationMicrousd": 500_000},
    "gpt-6-luna": {"inputTenths": 1, "outputTenths": 5, "reservationMicrousd": 50_000},
}


def configured_model():
    return os.environ.get("OPENAI_MODEL", "").strip() or DEFAULT_MODEL


def wake_pricing(model):
    if model not in WAKE_MODELS:
        raise ValueError("unsupported_wake_model")
    return WAKE_MODELS[model]
