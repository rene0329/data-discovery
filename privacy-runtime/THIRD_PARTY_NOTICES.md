# Third-party notices

This directory integrates, but does not relicense, the following projects:

- **Kuscia 1.2.0b0** and **SecretFlow 1.11.0b1**, Copyright Ant Group, Apache License 2.0.
- **SecretFlow PSI 0.6.0.dev260105**, Copyright Ant Group, Apache License 2.0. It includes Microsoft APSI and other transitive components whose notices remain in the upstream image/source distribution.
- **SFL** at `c383e40f665063d7f7d87e437a73e015f87c435c`, Copyright Ant Group, Apache License 2.0. Its Python dependencies retain their own licenses.
- **MP-SPDZ 0.4.3**, Copyright MP-SPDZ contributors, BSD 3-Clause. The release archive contains bundled dependencies and their upstream notices; those files are retained in `/opt/mp-spdz` in the resulting image.

Exact revisions and image digests are recorded in `dependencies.lock.json`. Release images must include an SPDX SBOM produced by `scripts/generate-sbom.sh`; a missing SBOM tool is a hard failure.
