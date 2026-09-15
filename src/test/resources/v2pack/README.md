# Palette v2 coverage pack

`palettes/every_kind_and_trait.json` exercises constructs that the bundled city pack may not use.
`V2PackGoldenTest` compiles it and checks the marker behavior, metadata and per-slot traits explicitly,
then compares a deterministic digest with `v2-pack.golden`.

`V2PackCoverageMutationTest` checks the assertions themselves. It removes or replaces sixteen named
constructs in independent in-memory copies. Every modified palette must still compile, and the same
coverage assertions used by `V2PackGoldenTest` must then fail. A decode error does not count as detecting
a lost construct. The tests run with the normal suite and can also run alone:

```sh
./gradlew test --tests '*V2PackCoverageMutationTest' --tests '*V2PackGoldenTest'
```

When adding a construct, add its named assertion and a mutation that removes its distinguishing
behavior. Check that the original passes and the mutation fails before changing a digest golden.
For example, deleting `when` must restore an installed block; using a missing block would let the
exclusion assertion pass for the wrong reason.

The pack tests compilation and palette queries. Use the separate generation digest checks to verify
chunk output; changing a coverage pack does not establish that world generation is unchanged.
