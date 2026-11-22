<?xml version="1.0" encoding="UTF-8"?>
<sch:schema xmlns:sch="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
  <sch:pattern>
    <sch:rule context="/doc">
      <sch:assert id="DOC-01" test="@ok = 'yes'">The attribute ok must be yes.</sch:assert>
    </sch:rule>
  </sch:pattern>
</sch:schema>
