import argparse
import json
import math
import subprocess
import sys
import tempfile
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(description="Extract burned-in subtitle OCR samples.")
    parser.add_argument("--video", required=True)
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--samples", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--min-score", type=float, default=0.88)
    parser.add_argument("--crop-top-ratio", type=float, default=0.56)
    parser.add_argument("--crop-height-ratio", type=float, default=0.40)
    parser.add_argument("--subtitle-y-min", type=float, default=0.52)
    parser.add_argument("--scale-width", type=int, default=1280)
    return parser.parse_args()


def has_cjk(text):
    return any("\u4e00" <= char <= "\u9fff" for char in text)


def mostly_numeric(text):
    useful = [char for char in text if not char.isspace()]
    if not useful:
        return True
    numeric = sum(1 for char in useful if char.isdigit() or char in ".,:/%Kk")
    return numeric / len(useful) >= 0.78


def clean_text(text):
    value = "".join(text.split())
    return value.strip("·•|_=- ")


def select_subtitle_line(result, width, height, min_score, subtitle_y_min, prefer_cjk):
    candidates = []
    for box, text, score in result or []:
        cleaned = clean_text(text)
        if score < min_score or len(cleaned) < 2 or len(cleaned) > 90 or mostly_numeric(cleaned):
            continue
        if prefer_cjk and not has_cjk(cleaned):
            continue

        xs = [point[0] for point in box]
        ys = [point[1] for point in box]
        center_x = sum(xs) / len(xs)
        center_y = sum(ys) / len(ys)
        center_x_ratio = center_x / max(1, width)
        center_y_ratio = center_y / max(1, height)
        if center_y_ratio < subtitle_y_min or not 0.14 <= center_x_ratio <= 0.86:
            continue

        cjk_bonus = 1.2 if has_cjk(cleaned) else 0
        y_bonus = center_y_ratio
        length_penalty = min(len(cleaned), 90) / 260
        score_value = score + cjk_bonus + y_bonus - length_penalty
        candidates.append(
            {
                "text": cleaned,
                "confidence": float(score),
                "centerYRatio": round(center_y_ratio, 4),
                "centerXRatio": round(center_x_ratio, 4),
                "rankScore": score_value,
            }
        )

    if not candidates:
        return None
    candidates.sort(key=lambda item: item["rankScore"], reverse=True)
    selected = candidates[0]
    selected.pop("rankScore", None)
    return selected


def sample_timestamps(start_ms, end_ms):
    if end_ms <= start_ms:
        return [max(0, start_ms) / 1000]
    duration = end_ms - start_ms
    points = [
        start_ms + min(900, max(120, duration * 0.35)),
        start_ms + duration * 0.65,
        max(start_ms, end_ms - 280),
    ]
    deduped = []
    for point in points:
        seconds = max(0, point / 1000)
        if all(abs(seconds - existing) > 0.18 for existing in deduped):
            deduped.append(seconds)
    return deduped


def extract_frame(ffmpeg, video, timestamp_seconds, output_path, crop_top_ratio, crop_height_ratio, scale_width):
    crop = f"crop=iw:ih*{crop_height_ratio}:0:ih*{crop_top_ratio},scale={scale_width}:-1"
    command = [
        ffmpeg,
        "-y",
        "-loglevel",
        "error",
        "-ss",
        f"{timestamp_seconds:.3f}",
        "-i",
        video,
        "-frames:v",
        "1",
        "-vf",
        crop,
        str(output_path),
    ]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def main():
    args = parse_args()
    with open(args.samples, "r", encoding="utf-8") as file:
        samples = json.load(file)

    try:
        from rapidocr_onnxruntime import RapidOCR
        from PIL import Image
    except Exception as exception:
        payload = {"available": False, "error": f"OCR runtime unavailable: {exception}", "samples": []}
        Path(args.output).write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        return 0

    ocr = RapidOCR()
    output_samples = []
    with tempfile.TemporaryDirectory(prefix="omnivid-ocr-", ignore_cleanup_errors=True) as temp_dir:
        temp_path = Path(temp_dir)
        for index, sample in enumerate(samples):
            start_ms = int(sample.get("startMs", 0))
            end_ms = int(sample.get("endMs", start_ms + 1))
            asr_text = sample.get("asrText", "")
            item = {
                "segmentIndex": int(sample.get("segmentIndex", index)),
                "startMs": start_ms,
                "endMs": end_ms,
                "asrText": asr_text,
                "timestampMs": 0,
                "ocrText": "",
                "confidence": 0.0,
                "available": False,
                "error": "",
            }
            try:
                best = None
                for attempt, timestamp_seconds in enumerate(sample_timestamps(start_ms, end_ms)):
                    frame_path = temp_path / f"frame-{index:05d}-{attempt}.png"
                    extract_frame(
                        args.ffmpeg,
                        args.video,
                        timestamp_seconds,
                        frame_path,
                        args.crop_top_ratio,
                        args.crop_height_ratio,
                        args.scale_width,
                    )
                    with Image.open(frame_path) as image:
                        width = image.width
                        height = image.height
                    result, _ = ocr(str(frame_path))
                    selected = select_subtitle_line(
                        result,
                        width,
                        height,
                        args.min_score,
                        args.subtitle_y_min,
                        has_cjk(asr_text),
                    )
                    if selected:
                        selected["timestampMs"] = int(math.floor(timestamp_seconds * 1000))
                        if not best or selected["confidence"] + selected["centerYRatio"] > best["confidence"] + best["centerYRatio"]:
                            best = selected
                if best:
                    item.update(
                        {
                            "ocrText": best["text"],
                            "confidence": best["confidence"],
                            "centerYRatio": best["centerYRatio"],
                            "centerXRatio": best["centerXRatio"],
                            "timestampMs": best["timestampMs"],
                            "available": True,
                        }
                    )
            except Exception as exception:
                item["error"] = str(exception)
            output_samples.append(item)

    payload = {"available": True, "error": "", "samples": output_samples}
    Path(args.output).write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
