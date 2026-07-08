#!/usr/bin/env python3
import argparse
import ast
import json
from pathlib import Path

import pyarrow.parquet as pq


def parse_questions(value):
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        return ast.literal_eval(value)
    raise TypeError(f"Unsupported probing_questions value: {type(value).__name__}")


def convert(parquet_path: Path, size: str, output_dir: Path) -> int:
    table = pq.read_table(parquet_path)
    rows = table.to_pylist()

    for row in rows:
        chat_id = str(row["conversation_id"])
        chat_dir = output_dir / size / chat_id
        questions_dir = chat_dir / "probing_questions"
        questions_dir.mkdir(parents=True, exist_ok=True)

        with (chat_dir / "chat.json").open("w", encoding="utf-8") as f:
            json.dump(row["chat"], f, ensure_ascii=False, indent=2)

        with (questions_dir / "probing_questions.json").open("w", encoding="utf-8") as f:
            json.dump(parse_questions(row["probing_questions"]), f, ensure_ascii=False, indent=2)

    return len(rows)


def main():
    parser = argparse.ArgumentParser(description="Convert BEAM parquet shards into benchmark chat directories.")
    parser.add_argument("--parquet", required=True, type=Path)
    parser.add_argument("--size", required=True)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()

    count = convert(args.parquet, args.size, args.output_dir)
    print(f"converted {count} BEAM {args.size} chats from {args.parquet}")


if __name__ == "__main__":
    main()
