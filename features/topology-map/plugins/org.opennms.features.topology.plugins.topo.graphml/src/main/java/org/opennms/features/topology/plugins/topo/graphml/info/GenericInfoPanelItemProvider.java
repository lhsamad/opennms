/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2016 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2016 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.features.topology.plugins.topo.graphml.info;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opennms.features.topology.api.GraphContainer;
import org.opennms.features.topology.api.info.InfoPanelItemProvider;
import org.opennms.features.topology.api.info.item.InfoPanelItem;
import org.opennms.features.topology.api.topo.AbstractVertex;
import org.opennms.features.topology.api.topo.EdgeRef;
import org.opennms.features.topology.api.topo.VertexRef;
import org.opennms.features.topology.plugins.topo.graphml.GraphMLVertex;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.measurements.api.MeasurementsService;
import org.opennms.netmgt.model.OnmsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.interpret.RenderResult;
import com.hubspot.jinjava.interpret.TemplateError;
import com.vaadin.shared.ui.label.ContentMode;
import com.vaadin.ui.Component;
import com.vaadin.ui.Label;

public class GenericInfoPanelItemProvider implements InfoPanelItemProvider {

    private final static Logger LOG = LoggerFactory.getLogger(GenericInfoPanelItemProvider.class);

    private final static Path DIR = Paths.get(System.getProperty("opennms.home"), "etc", "infopanel");

    // Workaround for OSGI-classloader-foo: Jinjava is using JUEL which
    // chooses the wrong classloader to load other dependencies. By
    // switching the classloader we can inject the correct one.
    private static <T> T withClassLoaderFix(final Supplier<T> supplier) {
        final ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(GenericInfoPanelItemProvider.class.getClassLoader());
            return supplier.get();
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    private final NodeDao nodeDao;

    private final Jinjava jinjava;

    private final MeasurementsService measurementsService;

    public GenericInfoPanelItemProvider(NodeDao nodeDao, MeasurementsService measurementsService) throws InstantiationException, IllegalAccessException {
        this.jinjava = withClassLoaderFix(Jinjava::new);
        this.nodeDao = Objects.requireNonNull(nodeDao);
        this.measurementsService = Objects.requireNonNull(measurementsService);
    }

    private class TemplateItem implements InfoPanelItem {

        final RenderResult renderResult;

        private TemplateItem(final RenderResult renderResult) {
            this.renderResult = renderResult;
        }

        @Override
        public Component getComponent() {
            return new Label(this.renderResult.getOutput(), ContentMode.HTML);
        }

        @Override
        public String getTitle() {
            return (String) this.renderResult.getContext().get("title", "No Title defined");
        }

        @Override
        public int getOrder() {
            return (int) (long) this.renderResult.getContext().get("order", 0L);
        }
    }

    private class ErrorItem implements InfoPanelItem {

        final Path path;
        final List<TemplateError> errors;

        private ErrorItem(final Path path,
                          final List<TemplateError> errors) {
            this.path = path;
            this.errors = errors;
        }

        @Override
        public Component getComponent() {
            final StringBuilder message = new StringBuilder();

            for (TemplateError error : this.errors) {
                message.append(error.getSeverity())
                        .append(": ")
                        .append(error.getMessage())
                        .append("@")
                        .append(error.getLineno())
                        .append("\n");
            }

            return new Label(message.toString(), ContentMode.PREFORMATTED);
        }

        @Override
        public String getTitle() {
            return "Error in " + this.path;
        }

        @Override
        public int getOrder() {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public Collection<InfoPanelItem> getContributions(final GraphContainer container) {
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(DIR, "*.html")) {
            final Set<InfoPanelItem> items = Sets.newHashSet();
            for (final Path path : stream) {
                try {
                    final RenderResult result = this.render(path, container);
                    if (result.hasErrors()) {
                        items.add(new ErrorItem(path, result.getErrors()));
                    } else if ((Boolean) result.getContext().getOrDefault("visible", false)) {
                        items.add(new TemplateItem(result));
                    }
                } catch (final IOException e) {
                    LOG.error("Failed to load template: {}: {}", path, e);
                    return Collections.emptySet();
                }
            }
            return items;
        } catch (final IOException e) {
            LOG.error("Failed to walk template directory: {}", DIR);
            return Collections.emptySet();
        }
    }

    // TODO how to populate the context correctly?
    private Map<String, Object> createVertexContext(final VertexRef vertex) {
        final Map<String, Object> context = Maps.newHashMap();
        if (vertex instanceof AbstractVertex) {
            final AbstractVertex abstractVertex = (AbstractVertex) vertex;
            if (abstractVertex.getNodeID() != null) {
                final OnmsNode node = this.nodeDao.get(abstractVertex.getNodeID());
                if (node != null) {
                    context.put("node", node);
                }
            }
        }
        if (vertex instanceof GraphMLVertex) {
            final GraphMLVertex atlasVertex = (GraphMLVertex) vertex;
            context.putAll(atlasVertex.getProperties());
        }
        context.put("vertex", vertex);
        return context;
    }

    // TODO how to populate the context correctly?
    private Map<String, Object> createEdgeContext(final EdgeRef edge) {
        final Map<String, Object> context = Maps.newHashMap();

        final HashMap<String, Object> edgeProperties = Maps.newHashMap();
        edgeProperties.put("LOGICAL_SITE_A", "CH33XC065-MW");
        edgeProperties.put("PROTECTION_SCHEME", "2+0");
        edgeProperties.put("LOGICAL_SITE_Z", "CH73Xc109-MW");
        edgeProperties.put("A_ESTIMATED_RSL_DBM", "TODO");
        edgeProperties.put("TOTAL_LINK_CAPACITY", "214");
        edgeProperties.put("RADIO_MODEL_A", "HP Quantum ODU Radio");
        edgeProperties.put("CLEARVISION_LINK_ID", null);
        edgeProperties.put("MICROWAVE_PATH_NAME", 101869801);
        edgeProperties.put("PATH_LENGTH_MILES", 6);
        edgeProperties.put("TRANSMIT_FREQ_A", "10995 1st MW ch  TODO 2nd MW ch");
        edgeProperties.put("TRANSMIT_FREQ_Z", "11485 1st MW ch  TODO 2nd MW ch");
        edgeProperties.put("DESIGNED_TX_MOD_TYPE", "32 QAM");

        context.put("edge", edgeProperties);

        return context;
    }

    private Map<String, Object> createContext(final GraphContainer container) {
        final Map<String, Object> context = Maps.newHashMap();

        Optional.ofNullable(Iterables.getOnlyElement(container.getSelectionManager().getSelectedEdgeRefs(), null))
                .map(this::createEdgeContext)
                .ifPresent(context::putAll);

        Optional.ofNullable(Iterables.getOnlyElement(container.getSelectionManager().getSelectedVertexRefs(), null))
                .map(this::createVertexContext)
                .ifPresent(context::putAll);

        context.put("measurements", new MeasurementsWrapper(measurementsService));

        return context;
    }

    private RenderResult render(final Path path,
                                final GraphContainer container) throws IOException {
        final Map<String, Object> context = this.createContext(container);

        final String template = Files.lines(path, Charset.defaultCharset())
                .collect(Collectors.joining("\n"));

        return withClassLoaderFix(() -> jinjava.renderForResult(template, context));
    }
}
