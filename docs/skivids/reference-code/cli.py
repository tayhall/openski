import argparse
import sys


def main():
    parser = argparse.ArgumentParser(description="OpenSki exploratory video measurements; no scores or ideal angles")
    commands = parser.add_subparsers(dest="command", required=True)
    analyse = commands.add_parser("analyse", help="Process a permitted local continuous-shot video")
    analyse.add_argument("--video", required=True)
    analyse.add_argument("--model", required=True)
    analyse.add_argument("--manifest", required=True)
    analyse.add_argument("--output", required=True)
    analyse.add_argument("--tracking-threshold", type=float, default=0.5,
                         help="Unvalidated landmark quality mask; not biomechanical confidence")
    summary = commands.add_parser("summarise", help="Summarise turns using manually reviewed boundaries")
    summary.add_argument("--analysis", required=True)
    summary.add_argument("--annotations", required=True)
    summary.add_argument("--output", required=True)
    summary.add_argument("--schema", default="schemas/turn.schema.json")
    args = parser.parse_args()
    try:
        if args.command == "analyse":
            from .pipeline import analyse as run
            path = run(args.video, args.model, args.manifest, args.output, args.tracking_threshold)
            print(f"Exploratory numerical outputs saved to {path}")
        else:
            from .summary import summarise
            count = summarise(args.analysis, args.annotations, args.output, args.schema)
            print(f"Saved {count} exploratory turn records to {args.output}")
    except (ValueError, OSError, ImportError) as error:
        print(f"OpenSki: {error}", file=sys.stderr)
        return 1
    return 0
