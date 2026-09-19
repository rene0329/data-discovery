#!/usr/bin/env python3
"""Generate the fixed recipient-mask MP-SPDZ source registry.

Mask bits map to participant order (bit 0=P0, bit 1=P1, bit 2=P2).  All seven
non-empty masks are compiled into the image; runtime requests can select only
one of these image-owned programs.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "programs"

SUM = """# Generated fixed malicious three-party integer vector sum.
MAX_VECTOR = 8
party_values = [sint.get_input_from(party, size=MAX_VECTOR) for party in range(3)]
for lane in range(MAX_VECTOR):
    total = sum(party_values[party][lane] for party in range(3))
{prints}
"""

STATS = """# Generated fixed malicious three-party fixed-point statistics.
MAX_VECTOR = 8
sfix.round_nearest = True
PARTIES = 3
values = [sfix.get_input_from(party, size=MAX_VECTOR) for party in range(PARTIES)]
for lane in range(MAX_VECTOR):
    lane_values = [values[party][lane] for party in range(PARTIES)]
    total = sum(lane_values)
    mean = total / PARTIES
    minimum = (lane_values[0] < lane_values[1]).if_else(lane_values[0], lane_values[1])
    minimum = (minimum < lane_values[2]).if_else(minimum, lane_values[2])
    maximum = (lane_values[0] < lane_values[1]).if_else(lane_values[1], lane_values[0])
    maximum = (maximum < lane_values[2]).if_else(lane_values[2], maximum)
    variance = sum((value - mean) * (value - mean) for value in lane_values) / PARTIES
{prints}
"""

THRESHOLD = """# Generated fixed malicious binary threshold program.
THRESHOLD = 100
total = sum(sint.get_input_from(party) for party in range(3))
reached = total >= THRESHOLD
{prints}
"""


def lines(mask, kind):
    result = []
    for player in range(3):
        if not mask & (1 << player):
            continue
        if kind == "sum":
            result.append(
                "    print_ln_to(%d, 'TOPIC4_SUM[%%s]=%%%%s' %% lane, "
                "total.reveal_to(%d))" % (player, player)
            )
        elif kind == "stats":
            result.append(
                "    print_ln_to(%d, 'TOPIC4_STATS[%%s] sum=%%%%s count=3 "
                "mean=%%%%s min=%%%%s max=%%%%s variance=%%%%s' %% lane, "
                "total.reveal_to(%d), mean.reveal_to(%d), minimum.reveal_to(%d), "
                "maximum.reveal_to(%d), variance.reveal_to(%d))"
                % (player, player, player, player, player, player)
            )
        else:
            result.append(
                "print_ln_to(%d, 'TOPIC4_THRESHOLD threshold=100 reached=%%s', "
                "reached.reveal_to(%d))" % (player, player)
            )
    return "\n".join(result)


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    for old in ROOT.glob("topic4_*_r*.mpc"):
        old.unlink()
    for mask in range(1, 8):
        (ROOT / ("topic4_secure_sum_r%d.mpc" % mask)).write_text(
            SUM.format(prints=lines(mask, "sum")), encoding="utf-8")
        (ROOT / ("topic4_private_stats_r%d.mpc" % mask)).write_text(
            STATS.format(prints=lines(mask, "stats")), encoding="utf-8")
        (ROOT / ("topic4_private_threshold_100_r%d.mpc" % mask)).write_text(
            THRESHOLD.format(prints=lines(mask, "threshold")), encoding="utf-8")


if __name__ == "__main__":
    main()
