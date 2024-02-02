package net.hydromatic.morel.compile;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.TypeSystem;

import java.util.HashMap;
import java.util.Map;

import static net.hydromatic.morel.ast.CoreBuilder.core;

class Uniquifier extends EnvShuttle {
  final Map<Core.IdPat, Core.IdPat> map;
  private final NameGenerator nameGenerator;

  protected Uniquifier(TypeSystem typeSystem, Environment env,
      NameGenerator nameGenerator, Map<Core.IdPat, Core.IdPat> map) {
    super(typeSystem, env);
    this.nameGenerator = nameGenerator;
    this.map = map;
  }

  protected static Core.Exp run(TypeSystem typeSystem,
      NameGenerator nameGenerator, Core.Exp exp, Environment env) {
    return exp.accept(create(typeSystem, nameGenerator, env));
  }

  protected static Uniquifier create(TypeSystem typeSystem,
      NameGenerator nameGenerator, Environment env) {
    return new Uniquifier(typeSystem, env, nameGenerator,
        new HashMap<>());
  }

  @Override
  protected EnvShuttle push(Environment env) {
    return new Uniquifier(typeSystem, env, nameGenerator, map);
  }

  @Override protected Core.IdPat visit(Core.IdPat idPat) {
    return map.computeIfAbsent(idPat,
        idPat1 -> {
          if (env.getOpt(idPat1) != null) {
            return idPat1; // already known
          }
          return core.idPat(idPat1.type, idPat1.name, nameGenerator);
        });
  }

  @Override protected Core.Exp visit(Core.Id id) {
    return core.id(id.idPat.accept(this));
  }
}
