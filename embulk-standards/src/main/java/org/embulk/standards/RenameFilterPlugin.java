package org.embulk.standards;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.Column;
import org.embulk.spi.FilterPlugin;
import org.embulk.spi.PageOutput;
import org.embulk.spi.Schema;

import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;


public class RenameFilterPlugin
        implements FilterPlugin
{
    public interface PluginTask
            extends Task
    {
        @Config("columns")
        @ConfigDefault("{}")
        Map<String, String> getRenameMap();

        @Config("rules")
        @ConfigDefault("[]")
        List<ConfigSource> getRulesList();
    }

    @Override
    public void transaction(ConfigSource config, Schema inputSchema,
                            FilterPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);
        Map<String, String> renameMap = task.getRenameMap();
        List<ConfigSource> rulesList = task.getRulesList();

        // check column_options is valid or not
        for (String columnName : renameMap.keySet()) {
            inputSchema.lookupColumn(columnName); // throws SchemaConfigException
        }

        // Rename by "columns": to be applied before "rules".
        Schema.Builder builder = Schema.builder();
        for (Column column : inputSchema.getColumns()) {
            String name = column.getName();
            if (renameMap.containsKey(name)) {
                name = renameMap.get(name);
            }
            builder.add(name, column.getType());
        }
        Schema intermediateSchema = builder.build();

        // Rename by "rules".
        Schema outputSchema = intermediateSchema;
        for (ConfigSource rule : rulesList) {
            outputSchema = applyRule(rule, intermediateSchema);
            intermediateSchema = outputSchema;
        }

        control.run(task.dump(), outputSchema);
    }

    @Override
    public PageOutput open(TaskSource taskSource, Schema inputSchema,
                           Schema outputSchema, PageOutput output)
    {
        return output;
    }


    // Extending Task is required to be deserialized with ConfigSource.loadConfig()
    // although this Rule is not really a Task.
    // TODO(dmikurube): Revisit this to consider how not to extend Task for this.
    private interface Rule
            extends Task
    {
        @Config("rule")
        @ConfigDefault("")
        String getRule();
    }

    private interface ConvertLowerCaseToUpperRule extends Rule {}
    private interface ConvertUpperCaseToLowerRule extends Rule {}

    private Schema applyRule(ConfigSource ruleConfig, Schema inputSchema) throws ConfigException
    {
        Rule rule = ruleConfig.loadConfig(Rule.class);
        switch (rule.getRule()) {
        case "convert_lower_case_to_upper":
            return applyConvertLowerCaseToUpper(inputSchema, ruleConfig.loadConfig(ConvertUpperCaseToLowerRule.class));
        case "convert_upper_case_to_lower":
            return applyConvertUpperCaseToLower(inputSchema, ruleConfig.loadConfig(ConvertLowerCaseToUpperRule.class));
        default:
            throw new ConfigException("Renaming operator \"" +rule+ "\" is unknown");
        }
    }

    private Schema applyConvertUpperCaseToLower(Schema inputSchema, ConvertLowerCaseToUpperRule rule) {
        Schema.Builder builder = Schema.builder();
        for (Column column : inputSchema.getColumns()) {
            builder.add(column.getName().toLowerCase(), column.getType());
        }
        return builder.build();
    }

    private Schema applyConvertLowerCaseToUpper(Schema inputSchema, ConvertUpperCaseToLowerRule rule) {
        Schema.Builder builder = Schema.builder();
        for (Column column : inputSchema.getColumns()) {
            builder.add(column.getName().toUpperCase(), column.getType());
        }
        return builder.build();
    }
}
